package com.example.cardscanner

import android.content.Context
import android.nfc.tech.IsoDep
import com.github.devnied.emvnfccard.parser.EmvTemplate
import java.util.Locale

class EmvCardReaderEngine(private val context: Context, private val isoDep: IsoDep) {

    data class ScanResult(
        val hexAid: String,
        val tagsMap: Map<String, String>,
        val rawHexDump: String,
        val apduPayloads: Map<String, String>
    )

    fun executeDeepScrape(): ScanResult? {
        isoDep.connect()
        isoDep.timeout = 5000

        val rawCardStreamBuffer = StringBuilder()
        val masterTagsMap = mutableMapOf<String, String>()
        val apduPayloads = linkedMapOf<String, String>()
        val parser = EmvTemplate.Builder()
            .setProvider(NfcProvider(isoDep))
            .setConfig(EmvTemplate.Config().setContactLess(true).setReadAllAids(true))
            .build()

        val cardData = try { parser.readEmvCard() } catch (e: Exception) { null }
        if (cardData != null) {
            val primaryApp = cardData.applications?.firstOrNull()
            val hexAid = primaryApp?.aid?.let { HexUtils.byteArrayToHexString(it) } ?: EmvStorage.DEFAULT_AID
            masterTagsMap["4F"] = hexAid

            val rawPan = cardData.cardNumber
            if (!rawPan.isNullOrEmpty() && !rawPan.contains("•")) {
                masterTagsMap["5A"] = rawPan.replace(" ", "")
            } else {
                val track2 = cardData.track2
                if (track2 != null && !track2.cardNumber.isNullOrEmpty()) {
                    masterTagsMap["5A"] = track2.cardNumber.replace(" ", "")
                }
            }
        }

        if (masterTagsMap["4F"].isNullOrBlank()) {
            masterTagsMap["4F"] = EmvStorage.DEFAULT_AID
        }

        collectCompatibilityFallback(rawCardStreamBuffer, masterTagsMap, apduPayloads)

        for ((payloadKey, payloadHex) in apduPayloads) {
            if (payloadKey.startsWith(EmvStorage.KEY_RECORD_PREFIX)) {
                HexUtils.extractLeafTags(payloadHex, masterTagsMap)
            }
        }

        if (!masterTagsMap.containsKey("82")) masterTagsMap["82"] = EmvStorage.DEFAULT_AIP
        if (!masterTagsMap.containsKey("94")) masterTagsMap["94"] = EmvStorage.DEFAULT_AFL
        if (!masterTagsMap.containsKey("5F24")) masterTagsMap["5F24"] = EmvStorage.DEFAULT_EXPIRY
        if (!masterTagsMap.containsKey("5F20")) masterTagsMap["5F20"] = ""
        if (!masterTagsMap.containsKey("5A")) masterTagsMap["5A"] = ""

        val prefs = context.getSharedPreferences(EmvStorage.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        editor.putString("HEX_AID", masterTagsMap["4F"] ?: EmvStorage.DEFAULT_AID)

        for ((tagCode, tagValue) in masterTagsMap) {
            editor.putString("TAG_$tagCode", tagValue)
        }
        for ((key, value) in apduPayloads) {
            editor.putString(key, value)
        }

        editor.commit()

        val spaceSeparatedDump = HexUtils.formatRawToSpaceSeparated(rawCardStreamBuffer.toString())
        return ScanResult(masterTagsMap["4F"] ?: EmvStorage.DEFAULT_AID, masterTagsMap, spaceSeparatedDump, apduPayloads)
    }

    private fun collectCompatibilityFallback(
        rawCardStreamBuffer: StringBuilder,
        masterTagsMap: MutableMap<String, String>,
        apduPayloads: MutableMap<String, String>
    ) {
        val selectCommands: List<Pair<String, String?>> = listOf(
            "00A404000E325041592E5359532E4444463031" to EmvStorage.KEY_APDU_PPSE, // PPSE
            "00A4040007${EmvStorage.DEFAULT_AID}" to EmvStorage.KEY_APDU_AID, // default Mastercard RID/PIX
            "00A4040007A0000000031010" to null // common fallback card AID
        )

        for ((commandHex, payloadKey) in selectCommands) {
            try {
                val response = isoDep.transceive(hexToBytes(commandHex))
                appendResponse(rawCardStreamBuffer, response, masterTagsMap, payloadKey, apduPayloads)
            } catch (e: Exception) {
                // Keep trying other selection candidates.
            }
        }

        collectRecordData(rawCardStreamBuffer, masterTagsMap, apduPayloads)
        collectChallengeData(rawCardStreamBuffer, masterTagsMap, apduPayloads)
    }

    private fun collectRecordData(
        rawCardStreamBuffer: StringBuilder,
        masterTagsMap: MutableMap<String, String>,
        apduPayloads: MutableMap<String, String>
    ) {
        for (sfi in 1..10) {
            for (record in 1..16) {
                val p2 = (sfi shl 3) or 4
                val readRecordCmd = byteArrayOf(0x00.toByte(), 0xB2.toByte(), record.toByte(), p2.toByte(), 0x00.toByte())
                try {
                    val response = isoDep.transceive(readRecordCmd)
                    appendResponse(rawCardStreamBuffer, response, masterTagsMap, recordKey(sfi, record), apduPayloads)
                } catch (e: Exception) {
                    // Some cards return 6A82 for absent records; that is fine.
                }
            }
        }
    }

    private fun collectChallengeData(
        rawCardStreamBuffer: StringBuilder,
        masterTagsMap: MutableMap<String, String>,
        apduPayloads: MutableMap<String, String>
    ) {
        try {
            masterTagsMap["9F37"] = "4A8B2C9E"

            val generateAcCmd = byteArrayOf(
                0x80.toByte(), 0xAE.toByte(), 0x80.toByte(), 0x00.toByte(), 0x1D.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x02.toByte(), 0x50.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x09.toByte(), 0x78.toByte(), 0x26.toByte(), 0x06.toByte(), 0x25.toByte(),
                0x00.toByte(), 0x4A.toByte(), 0x8B.toByte(), 0x2C.toByte(), 0x9E.toByte(), 0x00.toByte()
            )

            val response = isoDep.transceive(generateAcCmd)
            appendResponse(rawCardStreamBuffer, response, masterTagsMap, EmvStorage.KEY_APDU_AC, apduPayloads)
        } catch (e: Exception) {
            // Some emulators will not support GENERATE AC; keep the rest of the data.
        }
    }

    private fun appendResponse(
        rawCardStreamBuffer: StringBuilder,
        response: ByteArray,
        masterTagsMap: MutableMap<String, String>,
        payloadKey: String? = null,
        apduPayloads: MutableMap<String, String>? = null
    ) {
        val hexResponse = HexUtils.byteArrayToHexString(response)
        if (hexResponse.length <= 4) return
        val payload = hexResponse.substring(0, hexResponse.length - 4)
        rawCardStreamBuffer.append(payload)
        if (payloadKey != null && apduPayloads != null) {
            apduPayloads[payloadKey] = payload
        }
        if (hexResponse.endsWith("9000")) {
            HexUtils.parseRecursiveTLV(payload, masterTagsMap)
        }
    }

    private fun recordKey(sfi: Int, record: Int): String {
        return "${EmvStorage.KEY_RECORD_PREFIX}${String.format(Locale.US, "%02d_%02d", sfi, record)}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.uppercase(Locale.US)
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
