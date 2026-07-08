package com.example.cardscanner

import java.util.Locale
import kotlin.text.Charsets

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

    fun asciiToHexString(text: String): String {
        return text.toByteArray(Charsets.US_ASCII).joinToString("") { String.format("%02X", it) }
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

    fun extractLeafTags(hex: String, map: MutableMap<String, String>) {
        val constructedTags = setOf("6F", "70", "77", "A5", "61", "BF0C")
        var i = 0
        while (i < hex.length - 2) {
            var tag = hex.substring(i, i + 2)
            i += 2
            try {
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
                
                val isConstructed = (firstByteInt and 0x20 == 0x20) || upperTagKey in constructedTags
                if (!isConstructed) {
                    map[upperTagKey] = value.uppercase(Locale.US)
                } else {
                    extractLeafTags(value, map)
                }
                i += length * 2
            } catch (e: Exception) {
                break
            }
        }
    }
}
