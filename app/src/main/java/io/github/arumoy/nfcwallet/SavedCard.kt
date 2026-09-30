package io.github.arumoy.nfcwallet

import org.json.JSONObject
import java.util.UUID

/**
 * A payment card saved by the user.
 *
 * Security note: the [pan] (card number) and [cvv] are sensitive. They are only
 * ever persisted through [CardRepository], which encrypts the whole payload at
 * rest using an AES-256-GCM key held in the Android Keystore. The full PAN and
 * CVV are displayed in-app per the owner's explicit request.
 *
 * NOTE: Persisting the CVV is contrary to PCI-DSS, which prohibits storing the
 * card verification value. This app stores it only because it is a personal,
 * non-distributed build per the owner's explicit choice. Do not ship this.
 */
data class SavedCard(
    val id: String = UUID.randomUUID().toString(),
    val nickname: String,
    val cardholderName: String?,
    val pan: String,
    val expiry: String?,
    val cvv: String?,
    val network: String?
) {
    /**
     * Full card number formatted in groups of 4 for readability, e.g.
     * "4386 2812 3456 7890". The full PAN is shown per the owner's request
     * (personal, non-distributed build).
     */
    val formattedPan: String
        get() {
            val digits = pan.filter { it.isDigit() }
            if (digits.isEmpty()) return ""
            return digits.chunked(4).joinToString(" ")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NICKNAME, nickname)
        put(KEY_CARDHOLDER, cardholderName ?: JSONObject.NULL)
        put(KEY_PAN, pan)
        put(KEY_EXPIRY, expiry ?: JSONObject.NULL)
        put(KEY_CVV, cvv ?: JSONObject.NULL)
        put(KEY_NETWORK, network ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_CARDHOLDER = "cardholderName"
        private const val KEY_PAN = "pan"
        private const val KEY_EXPIRY = "expiry"
        private const val KEY_CVV = "cvv"
        private const val KEY_NETWORK = "network"

        fun fromJson(obj: JSONObject): SavedCard = SavedCard(
            id = obj.optString(KEY_ID, UUID.randomUUID().toString()),
            nickname = obj.optString(KEY_NICKNAME, ""),
            cardholderName = obj.optStringOrNull(KEY_CARDHOLDER),
            pan = obj.optString(KEY_PAN, ""),
            expiry = obj.optStringOrNull(KEY_EXPIRY),
            cvv = obj.optStringOrNull(KEY_CVV),
            network = obj.optStringOrNull(KEY_NETWORK)
        )

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "")
            return v.ifEmpty { null }
        }
    }
}
