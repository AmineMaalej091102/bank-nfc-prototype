package com.example.cardscanner

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.util.Locale

class MyCardEmulatorService : HostApduService() {

    private val ppseDfName = "325041592E5359532E4444463031" // 2PAY.SYS.DDF01
    private val mastercardLabel = "4D415354455243415244" // MASTERCARD
    private val defaultAid = EmvStorage.DEFAULT_AID.uppercase(Locale.US)

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return hexToBytes("6F00")
        val incomingHex = commandApdu.joinToString("") { String.format("%02X", it) }

        // CRITICAL: We fetch PREFS inside the function to force a disk-read on every tap
        val prefs = getSharedPreferences(EmvStorage.PREFS_NAME, Context.MODE_PRIVATE)

        val savedAid = (prefs.getString("HEX_AID", EmvStorage.DEFAULT_AID) ?: EmvStorage.DEFAULT_AID)
            .uppercase(Locale.US)
        val savedAip = (prefs.getString("TAG_82", EmvStorage.DEFAULT_AIP) ?: EmvStorage.DEFAULT_AIP).uppercase(Locale.US)
        val savedAfl = (prefs.getString("TAG_94", EmvStorage.DEFAULT_AFL) ?: EmvStorage.DEFAULT_AFL).uppercase(Locale.US)
        val allTags = prefs.all
            .asSequence()
            .mapNotNull { (key, value) ->
                if (!key.startsWith("TAG_") || value !is String) return@mapNotNull null
                val tag = key.removePrefix("TAG_")
                if (EmvTlvRules.isConstructed(tag)) return@mapNotNull null
                tag to normalizeTagValue(tag, value)
            }
            .toMap()

        // 1. SELECT PPSE / AID
        if (incomingHex.startsWith("00A40400")) {
            return when {
                incomingHex.contains(ppseDfName) -> buildSelectResponse(ppseDfName, savedAid)
                incomingHex.contains(savedAid) || incomingHex.contains(defaultAid) -> buildSelectResponse(savedAid, savedAid)
                else -> hexToBytes("6A82")
            }
        }

        // 2. GPO
        if (incomingHex.startsWith("80A80000")) {
            val gpoPayload = prefs.getString(EmvStorage.KEY_APDU_GPO, null)
            return if (gpoPayload != null) {
                hexToBytes(gpoPayload + "9000")
            } else {
                val afl = buildAfl()
                hexToBytes(tlv("77", tlv("82", savedAip) + tlv("94", afl)) + "9000")
            }
        }

        // 3. READ RECORD
        if (incomingHex.startsWith("00B2")) {
            val sfi = ((commandApdu[3].toInt() and 0xF8) shr 3)
            val recordNumber = commandApdu[2].toInt() and 0xFF
            val recordPayload = prefs.getString(recordKey(sfi, recordNumber), null) ?: buildRecordPayload(sfi, recordNumber, allTags)
            return if (recordPayload.isNotEmpty()) {
                if (recordPayload.startsWith("70")) hexToBytes(recordPayload + "9000") else hexToBytes(tlv("70", recordPayload) + "9000")
            } else {
                hexToBytes("6A82")
            }
        }
        return hexToBytes("6A82")
    }

    override fun onDeactivated(reason: Int) {}
    private fun buildSelectResponse(dfNameHex: String, aidHex: String): ByteArray {
        val stored = if (dfNameHex == ppseDfName) EmvStorage.KEY_APDU_PPSE else EmvStorage.KEY_APDU_AID
        val response = getSharedPreferences(EmvStorage.PREFS_NAME, Context.MODE_PRIVATE).getString(stored, null)
        if (response != null) {
            return hexToBytes(response + "9000")
        }
        val fci = tlv(
            "6F",
            tlv("84", dfNameHex) +
                tlv("A5", tlv("61", tlv("4F", aidHex) + tlv("50", mastercardLabel)))
        )
        return hexToBytes(fci + "9000")
    }

    private fun buildRecordPayload(sfi: Int, recordNumber: Int, tags: Map<String, String>): String {
        val coreTags = listOf("4F", "50", "5A", "5F20", "5F24")
        val profileTags = listOf("82", "94", "9F06", "9F10", "9F26", "9F27", "9F36", "9F37", "9F1A", "9F02", "9F03")
        val extraTags = tags.keys
            .filterNot { it in coreTags || it in profileTags || it == "HEX_AID" }
            .sorted()

        val selectedTags = when {
            sfi <= 1 && recordNumber == 1 -> coreTags
            sfi <= 1 && recordNumber == 2 -> profileTags
            sfi == 2 && recordNumber == 1 -> extraTags
            else -> emptyList()
        }

        val payload = StringBuilder()
        for (tagCode in selectedTags) {
            val tagValue = tags[tagCode] ?: continue
            if (tagValue.isNotBlank()) {
                payload.append(tlv(tagCode, tagValue))
            }
        }
        return payload.toString()
    }

    private fun buildAfl(): String {
        // Advertise SFI 1 core/profile records and SFI 2 extra tags.
        return "0801020010010100"
    }

    private fun tlv(tag: String, valueHex: String): String {
        val cleanValue = valueHex.uppercase(Locale.US)
        val length = cleanValue.length / 2
        return tag.uppercase(Locale.US) + String.format("%02X", length) + cleanValue
    }

    private fun normalizeTagValue(tag: String, value: String): String {
        val clean = value.replace(" ", "").uppercase(Locale.US)
        return when (tag) {
            "5A" -> if (clean.length % 2 == 0) clean else clean + "F"
            "5F20" -> if (isHex(clean)) clean else HexUtils.asciiToHexString(value)
            "5F24", "82", "94", "9F06", "9F10", "9F26", "9F27", "9F36", "9F37", "9F1A", "9F02", "9F03" -> clean
            else -> if (isHex(clean)) clean else HexUtils.asciiToHexString(value)
        }
    }

    private fun isHex(value: String): Boolean {
        return value.isNotEmpty() && value.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun recordKey(sfi: Int, record: Int): String {
        return "${EmvStorage.KEY_RECORD_PREFIX}${String.format(Locale.US, "%02d_%02d", sfi, record)}"
    }

    private fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
