package com.example.cardscanner

import android.nfc.tech.IsoDep
import com.github.devnied.emvnfccard.parser.IProvider
import com.github.devnied.emvnfccard.exception.CommunicationException
import java.io.IOException

class NfcProvider(private val isoDep: IsoDep) : IProvider {

    override fun transceive(command: ByteArray?): ByteArray {
        if (command == null) return byteArrayOf()
        return try {
            if (!isoDep.isConnected) {
                isoDep.connect()
            }
            // Pass the data cleanly over the radio antenna
            isoDep.transceive(command)
        } catch (e: IOException) {
            // CRITICAL FIX: Translate the antenna drop into an EMV communication warning
            // instead of crashing out. This keeps the reader hardware alive!
            throw CommunicationException(e.message)
        }
    }

    override fun getAt(): ByteArray {
        return try {
            isoDep.historicalBytes ?: isoDep.hiLayerResponse ?: byteArrayOf()
        } catch (e: Exception) {
            byteArrayOf()
        }
    }
}
