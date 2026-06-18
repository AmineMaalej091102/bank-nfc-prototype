package com.example.cardscanner

import android.nfc.tech.IsoDep
import com.github.devnied.emvnfccard.parser.IProvider

class NfcProvider(private val isoDep: IsoDep) : IProvider {

    // Connects the EMV engine directly to the card radio frequency channel
    override fun transceive(command: ByteArray?): ByteArray {
        if (command == null) return byteArrayOf()
        return isoDep.transceive(command)
    }

    override fun getAt() = isoDep.historicalBytes ?: byteArrayOf()
}
