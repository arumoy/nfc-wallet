package io.github.arumoy.nfcwallet

import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException

class EmvReader(private val isoDep: IsoDep) {

    private val PPSE_AID = "325041592E5359532E4444463031" // "2PAY.SYS.DDF01" in hex

    fun readCard(): CardData {
        isoDep.connect()
        try {
            // 1. Select PPSE
            val ppseResponse = transceive("00A40400", PPSE_AID)
            if (!isSuccess(ppseResponse)) return CardData("Failed to select PPSE")

            // Parse AIDs from PPSE response
            val tlvs = TlvParser.parseAll(ppseResponse)
            val aidTlv = tlvs.firstOrNull { it.tag == "4F" }
            if (aidTlv == null) return CardData("No AID found in PPSE")

            val aid = aidTlv.value.toHexString()
            val upperAid = aid.uppercase()
            val cardType = when {
                upperAid.startsWith("A000000003") -> "Visa"
                upperAid.startsWith("A000000004") -> "Mastercard"
                upperAid.startsWith("A000000025") -> "American Express"
                upperAid.startsWith("A000000152") -> "Discover / Diners Club"
                upperAid.startsWith("A000000524") -> "RuPay"
                else -> "Unknown Network"
            }

            // 2. Select AID
            val aidResponse = transceive("00A40400", aid)
            if (!isSuccess(aidResponse)) return CardData("Failed to select AID $aid")

            // 3. Get Processing Options (GPO)
            val gpoData = constructGpoData(aidResponse, aid)
            val gpoResponse = transceive("80A80000", gpoData)
            if (!isSuccess(gpoResponse)) {
                return CardData("GPO Failed (SW: ${"%02X%02X".format(gpoResponse[gpoResponse.size-2], gpoResponse[gpoResponse.size-1])}) with Data: $gpoData")
            }
            
            // Extract AFL (Application File Locator) from GPO response
            // GPO response might be Format 1 (starts with 80) or Format 2 (starts with 77)
            val gpoTlvs = TlvParser.parseAll(gpoResponse)
            val aflBytes = gpoTlvs.firstOrNull { it.tag == "94" }?.value ?: extractAflFormat1(gpoResponse)

            if (aflBytes == null || aflBytes.isEmpty()) {
                return CardData("AFL not found")
            }

            // 4. Read Records based on AFL
            val records = mutableListOf<ByteArray>()
            var i = 0
            while (i < aflBytes.size) {
                val sfi = (aflBytes[i].toInt() shr 3) and 0x1F
                val firstRecord = aflBytes[i + 1].toInt()
                val lastRecord = aflBytes[i + 2].toInt()
                // aflBytes[i+3] is offline data authentication records

                for (record in firstRecord..lastRecord) {
                    val p1 = "%02X".format(record)
                    val p2 = "%02X".format((sfi shl 3) or 0x04)
                    val recordResponse = transceive("00B2$p1$p2", "")
                    if (isSuccess(recordResponse)) {
                        records.add(recordResponse)
                    }
                }
                i += 4
            }

            // Parse records
            val allTlvs = mutableListOf<Tlv>()
            allTlvs.addAll(TlvParser.parseAll(aidResponse)) // FCI may carry cardholder name (5F20)
            allTlvs.addAll(gpoTlvs) // Include GPO response tags
            records.forEach { 
                allTlvs.addAll(TlvParser.parseAll(it)) 
            }

            // Cardholder name: EMV tag 5F20, ASCII-encoded. Often absent or a
            // filler value ("/", " ") on contactless cards for privacy.
            val cardholderName = extractCardholderName(allTlvs)

            // Try to find Track 2 Equivalant Data (Tag 57)
            val track2 = allTlvs.firstOrNull { it.tag == "57" }?.value?.toHexString() 
                ?: allTlvs.firstOrNull { it.tag == "9F6B" }?.value?.toHexString() // Alternate Track 2 for Contactless

            var extractedPan: String? = null
            var extractedExpiry: String? = null

            if (track2 != null) {
                val parts = track2.split("D", "d", "F", "f") // Sometimes padded with F
                if (parts.size > 1) {
                    extractedPan = parts[0]
                    extractedExpiry = parts[1].take(4)
                }
            } else {
                // Fallback to Tag 5A (PAN) and 5F24 (Expiry) if Tag 57 is missing
                extractedPan = allTlvs.firstOrNull { it.tag == "5A" }?.value?.toHexString()?.replace("F", "")?.replace("f", "")
                extractedExpiry = allTlvs.firstOrNull { it.tag == "5F24" }?.value?.toHexString()?.take(4)
            }
            
            val formattedExpiry = extractedExpiry?.let {
                if (it.length == 4) {
                    val yy = it.substring(0, 2)
                    val mm = it.substring(2, 4)
                    "$mm/$yy"
                } else {
                    it
                }
            }

            return CardData(
                pan = extractedPan,
                expiry = formattedExpiry,
                cardType = cardType,
                cardholderName = cardholderName
            )

        } catch (e: IOException) {
            return CardData(error = "NFC Communication Error: ${e.message}")
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
        }
    }

    private fun constructGpoData(aidResponse: ByteArray, aid: String): String {
        val tlvs = TlvParser.parseAll(aidResponse)
        val pdolTlv = tlvs.firstOrNull { it.tag.equals("9F38", ignoreCase = true) }
        
        if (pdolTlv == null) {
            return "8300"
        }
        
        val pdolBytes = pdolTlv.value
        val gpoPayload = mutableListOf<Byte>()
        var i = 0
        
        while (i < pdolBytes.size) {
            val tagByte1 = pdolBytes[i].toInt() and 0xFF
            val tagBytes = mutableListOf<Byte>(pdolBytes[i])
            i++
            
            if ((tagByte1 and 0x1F) == 0x1F) {
                var hasNext = true
                while (hasNext && i < pdolBytes.size) {
                    val nextByte = pdolBytes[i]
                    tagBytes.add(nextByte)
                    i++
                    hasNext = (nextByte.toInt() and 0x80) != 0
                }
            }
            val tagHex = tagBytes.toByteArray().toHexString().uppercase()
            
            if (i >= pdolBytes.size) break
            val length = pdolBytes[i].toInt() and 0xFF
            i++
            
            val value = getPdolValue(tagHex, length, aid)
            Log.d("EmvReader", "PDOL Requested Tag: $tagHex, Length: $length, Mocked Value: ${value.toHexString()}")
            gpoPayload.addAll(value.toList())
        }
        
        val pdolHex = gpoPayload.toByteArray().toHexString()

        // GPO command structure: 83 [Length of following data] [Data]
        // But the LC we send in transceive represents the total length of the 83... object
        val totalLengthHex = "%02X".format(gpoPayload.size)
        return "83$totalLengthHex$pdolHex"
    }

    private fun getPdolValue(tag: String, length: Int, aid: String): ByteArray {
        val upperAid = aid.uppercase()
        val isMastercard = upperAid.startsWith("A000000004")
        val isVisa = upperAid.startsWith("A000000003")
        val isAmex = upperAid.startsWith("A000000025")
        
        val hexValue = if (tag == "9F6E" && isMastercard) {
             "06088000" // Form Factor/Third Party - required by Mastercard
        } else if (tag == "9F6E" && isAmex) {
             "30000000" // Enhanced Contactless Reader Capabilities - required by Amex
        } else if (tag == "9F66" && isVisa) {
             "28004000" // Terminal Transaction Qualifiers (TTQ) - required by Visa
        } else if (tag == "9F35") {
             "22"       // Terminal Type (22 = Unattended/Offline) - often required by Amex
        } else if (tag == "9F40") {
             "F000F0A001" // Additional Terminal Capabilities (common default)
        } else if (tag == "9F33") {
             "E04000"     // Terminal Capabilities
        } else if (tag == "9A") {
             "260101"     // Transaction Date (YYMMDD)
        } else if (tag == "9F09") {
             "0002"       // Application Version Number (required by RuPay)
        } else if (tag == "9F37") {
             "823DDE7A"   // Unpredictable Number (required by RuPay)
        } else {
             "".padStart(length * 2, '0') // Pad with zeros for all other tags
        }
        
        val paddedOrTruncated = if (hexValue.length > length * 2) {
            hexValue.take(length * 2)
        } else {
            hexValue.padStart(length * 2, '0')
        }
        return paddedOrTruncated.decodeHex()
    }

    /**
     * Extracts the cardholder name from EMV tag 5F20 (ASCII per EMV Book 3).
     * Returns null when the tag is absent, empty, or a filler value that some
     * cards emit instead of a real name (e.g. "/", "UNKNOWN").
     */
    private fun extractCardholderName(tlvs: List<Tlv>): String? {
        val bytes = tlvs.firstOrNull { it.tag.equals("5F20", ignoreCase = true) }?.value ?: return null
        val text = bytes
            .filter { (it.toInt() and 0xFF) in 0x20..0x7E } // printable ASCII only
            .toByteArray()
            .toString(Charsets.US_ASCII)
            .trim()
        // Collapse internal whitespace runs.
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val fillers = setOf("", "/", "UNKNOWN", "CARDHOLDER", "CARD HOLDER", "VALUED CUSTOMER")
        return if (normalized.uppercase() in fillers) null else normalized
    }

    private fun extractAflFormat1(response: ByteArray): ByteArray? {
        if (response.size >= 4 && response[0] == 0x80.toByte()) {
            val length = response[1].toInt() and 0xFF
            if (response.size >= 2 + length) {
                 // Format 1: 80 length AIP(2 bytes) AFL(var)
                 if (length > 2) {
                     return response.copyOfRange(4, 2 + length)
                 }
            }
        }
        return null
    }

    private fun transceive(header: String, data: String): ByteArray {
        val lc = if (data.isNotEmpty()) "%02X".format(data.length / 2) else ""
        val le = "00" // Expected length
        val command = header + lc + data + le
        return isoDep.transceive(command.decodeHex())
    }

    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size >= 2) {
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            return sw1 == 0x90 && sw2 == 0x00
        }
        return false
    }
}

data class CardData(
    val error: String? = null,
    val pan: String? = null,
    val expiry: String? = null,
    val cardType: String? = null,
    val cardholderName: String? = null
)