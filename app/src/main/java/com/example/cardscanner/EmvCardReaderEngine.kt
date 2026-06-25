package com.example.cardscanner

import android.nfc.tech.IsoDep
import com.github.devnied.emvnfccard.parser.EmvTemplate
import java.util.Locale

class EmvCardReaderEngine(private val isoDep: IsoDep) {

    data class ScanResult(
        val hexAid: String,
        val tagsMap: Map<String, String>,
        val rawHexDump: String
    )

    fun executeDeepScrape(): ScanResult? {
        isoDep.connect()
        isoDep.timeout = 5000
        val rawCardStreamBuffer = java.lang.StringBuilder()

        val provider = NfcProvider(isoDep)
        val config = EmvTemplate.Config().setContactLess(true).setReadAllAids(true)
        val parser = EmvTemplate.Builder().setProvider(provider).setConfig(config).build()
        val cardData = try { parser.readEmvCard() } catch (e: Exception) { null } ?: return null

        val masterTagsMap = mutableMapOf<String, String>()
        val primaryApp = cardData.applications?.firstOrNull()
        val hexAid = primaryApp?.aid?.let { HexUtils.byteArrayToHexString(it) } ?: "A0000000041010"
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

        // Query the SFI record files natively
        for (sfi in 1..4) {
            for (record in 1..12) {
                val p2 = (sfi shl 3) or 4
                val readRecordCmd = byteArrayOf(0x00.toByte(), 0xB2.toByte(), record.toByte(), p2.toByte(), 0x00.toByte())
                try {
                    val recordResponse = isoDep.transceive(readRecordCmd)
                    val hexResponse = HexUtils.byteArrayToHexString(recordResponse)
                    if (hexResponse.length > 4) {
                        rawCardStreamBuffer.append(hexResponse.substring(0, hexResponse.length - 4))
                        if (hexResponse.endsWith("9000")) {
                            HexUtils.parseRecursiveTLV(hexResponse.substring(0, hexResponse.length - 4), masterTagsMap)
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        // Generate the dynamic ARQC cryptogram
        try {
            val generateAcCmd = byteArrayOf(
                0x80.toByte(), 0xAE.toByte(), 0x80.toByte(), 0x00.toByte(), 0x1D.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x02.toByte(), 0x50.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x09.toByte(), 0x78.toByte(), 0x26.toByte(), 0x06.toByte(), 0x24.toByte(),
                0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x00.toByte()
            )
            val cryptoResponse = isoDep.transceive(generateAcCmd)
            val cryptoHex = HexUtils.byteArrayToHexString(cryptoResponse)
            if (cryptoHex.length > 4) {
                rawCardStreamBuffer.append(cryptoHex.substring(0, cryptoHex.length - 4))
                if (cryptoHex.endsWith("9000")) {
                    HexUtils.parseRecursiveTLV(cryptoHex.substring(0, cryptoHex.length - 4), masterTagsMap)
                }
            }
        } catch (e: Exception) {}

        val spaceSeparatedDump = HexUtils.formatRawToSpaceSeparated(rawCardStreamBuffer.toString())
        return ScanResult(hexAid, masterTagsMap, spaceSeparatedDump)
    }
}
