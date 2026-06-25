package com.example.cardscanner

import java.util.Locale

object HexUtils {

    fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    fun formatAidToColonHex(aid: String): String {
        return aid.uppercase(Locale.US).chunked(2).joinToString(":")
    }

    fun formatRawToSpaceSeparated(rawLog: String): String {
        return rawLog.uppercase(Locale.US).chunked(2).joinToString(" ")
    }

    fun parseRecursiveTLV(hex: String, map: MutableMap<String, String>) {
        var i = 0
        while (i < hex.length - 2) {
            var tag = hex.substring(i, i + 2)
            i += 2
            val firstByteInt = tag.toInt(16)
            if ((firstByteInt and 0x1F) == 0x1F) {
                if (i + 2 <= hex.length) {
                    tag += hex.substring(i, i + 2)
                    i += 2
                }
            }
            if (i + 2 > hex.length) break
            val lenByte = hex.substring(i, i + 2)
            i += 2
            var length = lenByte.toInt(16)
            if (length and 0x80 != 0) {
                val numSubsequentBytes = length and 0x7F
                if (i + numSubsequentBytes * 2 <= hex.length) {
                    val lengthValueStr = hex.substring(i, i + numSubsequentBytes * 2)
                    length = lengthValueStr.toInt(16)
                    i += numSubsequentBytes * 2
                }
            }
            if (i + length * 2 > hex.length) break
            val value = hex.substring(i, i + length * 2)
            val upperTagKey = tag.uppercase(Locale.US)
            map[upperTagKey] = value.uppercase(Locale.US)

            if ((firstByteInt and 0x20 == 0x20) ||
                upperTagKey == "70" || upperTagKey == "77" ||
                upperTagKey == "A5" || upperTagKey == "61" ||
                upperTagKey == "BF0C" || upperTagKey == "6F") {
                parseRecursiveTLV(value, map)
            }
            i += length * 2
        }
    }
}
