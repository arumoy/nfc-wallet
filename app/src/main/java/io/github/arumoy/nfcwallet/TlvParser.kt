package io.github.arumoy.nfcwallet

class Tlv(val tag: String, val length: Int, val value: ByteArray) {
    override fun toString(): String {
        return "Tag: $tag, Length: $length, Value: ${value.toHexString()}"
    }
}

object TlvParser {

    fun parseAll(data: ByteArray): List<Tlv> {
         val tlvs = mutableListOf<Tlv>()
         var i = 0
         while (i < data.size) {
             // skip 0x00 and 0xFF padding
             if (data[i] == 0x00.toByte() || data[i] == 0xFF.toByte()) {
                 i++
                 continue
             }

             val tagByte1 = data[i].toInt() and 0xFF
             val tagBytes = mutableListOf<Byte>(data[i])
             i++
             
             if ((tagByte1 and 0x1F) == 0x1F) {
                 var hasNext = true
                 while (hasNext && i < data.size) {
                     val nextByte = data[i]
                     tagBytes.add(nextByte)
                     i++
                     hasNext = (nextByte.toInt() and 0x80) != 0
                 }
             }
             val tag = tagBytes.toByteArray().toHexString()

             if (i >= data.size) break

             var length = data[i].toInt() and 0xFF
             i++
             if (length > 127) {
                 val numLengthBytes = length and 0x7F
                 length = 0
                 for (j in 0 until numLengthBytes) {
                     if (i >= data.size) break
                     length = (length shl 8) or (data[i].toInt() and 0xFF)
                     i++
                 }
             }

             if (i + length > data.size) break
             
             val value = data.copyOfRange(i, i + length)
             tlvs.add(Tlv(tag, length, value))
             
             // If constructed (bit 6 is 1), also parse children
             if ((tagByte1 and 0x20) == 0x20) {
                 tlvs.addAll(parseAll(value))
             }

             i += length
         }
         return tlvs
    }
}

// Extension function to convert byte array to hex string
fun ByteArray.toHexString(): String = joinToString(separator = "") { eachByte -> "%02X".format(eachByte) }
fun String.decodeHex(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}