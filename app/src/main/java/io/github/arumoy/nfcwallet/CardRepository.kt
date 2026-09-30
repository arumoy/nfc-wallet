package io.github.arumoy.nfcwallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists [SavedCard]s to device storage with the card payload encrypted at rest.
 *
 * Encryption: AES-256-GCM using a hardware-backed key generated in and never
 * exported from the Android Keystore. GCM provides authenticated encryption
 * (confidentiality + integrity). Each write uses a fresh random 12-byte IV,
 * stored as a prefix of the ciphertext blob. The blob is Base64-encoded into a
 * private SharedPreferences file.
 *
 * We use the Keystore-managed AES primitive rather than rolling custom crypto,
 * and rather than the deprecated Jetpack Security (EncryptedSharedPreferences)
 * library.
 */
class CardRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns all saved cards. Returns an empty list if none or on decrypt failure (fail-safe). */
    fun getCards(): List<SavedCard> {
        val stored = prefs.getString(KEY_CARDS, null) ?: return emptyList()
        return try {
            val json = decrypt(stored)
            val array = JSONArray(json)
            (0 until array.length()).map { SavedCard.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            // Never expose crypto/parse internals to the user; fail closed with an empty list.
            Log.e(TAG, "Failed to read saved cards: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** Adds a card and persists. Returns the updated list. */
    fun addCard(card: SavedCard): List<SavedCard> {
        val updated = getCards() + card
        persist(updated)
        return updated
    }

    /** Deletes a card by id and persists. Returns the updated list. */
    fun deleteCard(id: String): List<SavedCard> {
        val updated = getCards().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    /** Replaces an existing card (matched by id) and persists. Returns the updated list. */
    fun updateCard(card: SavedCard): List<SavedCard> {
        val updated = getCards().map { if (it.id == card.id) card else it }
        persist(updated)
        return updated
    }

    private fun persist(cards: List<SavedCard>) {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        val encrypted = encrypt(array.toString())
        prefs.edit().putString(KEY_CARDS, encrypted).apply()
    }

    // --- Encryption -------------------------------------------------------

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // Store as: [IV length (1 byte)][IV][ciphertext], Base64-encoded.
        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        val ivSize = combined[0].toInt()
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "CardRepository"
        private const val PREFS_NAME = "nfc_wallet_cards"
        private const val KEY_CARDS = "cards_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nfc_wallet_card_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
