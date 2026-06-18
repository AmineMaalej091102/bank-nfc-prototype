package com.example.cardscanner

import android.annotation.SuppressLint
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusEmoji: TextView
    private lateinit var statusText: TextView
    private lateinit var txtAid: TextView
    private lateinit var txtLabel: TextView
    private lateinit var txtTlvOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        statusEmoji = findViewById(R.id.statusEmoji)
        statusText = findViewById(R.id.statusText)
        txtAid = findViewById(R.id.txtAid)
        txtLabel = findViewById(R.id.txtLabel)
        txtTlvOutput = findViewById(R.id.txtTlvOutput)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        val options = Bundle()
        // Low-latency Reader Mode minimizes threading interference
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // Triggers inside a dedicated background worker thread when card breaches antenna path
    @SuppressLint("SetTextI18n")
    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            updateUiConnecting()

            // 1. SELECT PPSE Command (Proximity Payment System Environment)
            // This queries the chip to discover the location path of the master financial app
            val selectPpseCmd = hexStringToByteArray("00A404000E325041592E5359532E444446303100")
            val response = isoDep.transceive(selectPpseCmd)

            val hexResponse = byteArrayToHexString(response)

            // Validate response ending status bytes (9000 = Success OK)
            if (hexResponse.endsWith("9000")) {
                val parsedTags = mutableMapOf<String, String>()
                parseTLV(hexResponse, parsedTags)

                // EMV standard fields: Tag 4F = AID, Tag 50 = Application Label String
                val extractedAid = parsedTags["4F"] ?: "A0000000031010" // Default fallback to Visa standard
                val cardLabelText = parsedTags["50"] ?.let { hexToString(it)} ?: "Standard Bank Card"

                // 2. Read Internal Records using the discovered AID profile
                // This builds the precise dynamic log stack to pipe into a terminal/SoftPOS environment
                val selectAidCmd = hexStringToByteArray("00A40400" + String.format("%02X", extractedAid.length / 2) + extractedAid + "00")
                val aidResponse = isoDep.transceive(selectAidCmd)
                val hexAidResponse = byteArrayToHexString(aidResponse)

                val detailedTags = mutableMapOf<String, String>()
                parseTLV(hexAidResponse, detailedTags)

                runOnUiThread {
                    statusCard.setCardBackgroundColor("#198754".toColorInt())// Success Green
                    statusEmoji.text = "✅"
                    statusText.text = "Card Read Complete!"
                    txtAid.text = "Application ID (AID): $extractedAid"
                    txtLabel.text = "Card Network: $cardLabelText"

                    // Render out the dynamic layout block containing the TLV structure
                    val displayBuilder = StringBuilder()
                    detailedTags.forEach { (tag, value) -> displayBuilder.append("Tag [").append(tag).append("] | Len: ").append(value.length / 2)
                        .append("\n└─ Value: ").append(value).append("\n\n")
                    }
                    txtTlvOutput.text = displayBuilder.toString()
                }
            } else {
                showUiError("Unsupported Card Type")
            }
        } catch (_: Exception) {
            showUiError("Card lost too fast. Try again!")
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    // Non-blocking recursive execution logic to dissect the raw-length-value packets
    private fun parseTLV(hex: String, map: MutableMap<String, String>) {
        var i = 0
        while (i < hex.length - 4) {
            // Read first byte to determine Tag footprint
            var tag = hex.substring(i, i + 2)
            i += 2

            // Handling multibyte EMV tags (if bits 1-5 of first byte are all 1s / hex content '1F')
            val firstByteInt = tag.toInt(16)
            if ((firstByteInt and 0x1F) == 0x1F) {
                tag += hex.substring(i, i + 2)
                i += 2
            }

            // Read length byte
            val lenByte = hex.substring(i, i + 2)
            i += 2
            var length = lenByte.toInt(16)

            // Multibyte length parsing handling
            if (length and 0x80 != 0) {
                val numSubsequentBytes = length and 0x7F
                if (i + numSubsequentBytes * 2 <= hex.length) {
                    val extendedLenStr = hex.substring(i, i + numSubsequentBytes * 2)
                    length = extendedLenStr.toInt(16)
                    i += numSubsequentBytes * 2
                }
            }

            if (i + length * 2 > hex.length) break

            val value = hex.substring(i, i + length * 2)
            map[tag.uppercase(Locale.ROOT)] = value.uppercase(Locale.ROOT)

            // Construct Nesting rules: If tag is an EMV template folder, parse its contents
            if (tag == "6F" || tag == "A5" || tag == "BF0C") {
                parseTLV(value, map)
            }
            i += length * 2
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiConnecting() {
        runOnUiThread {
            statusCard.setCardBackgroundColor("#FFC107".toColorInt()) // Warning Yellow
            statusEmoji.text = "\uD83D\uDD04"
            statusText.text = "Reading Chip Data... Do Not Move Card"
        }
    }

    private fun showUiError(message: String) {
        runOnUiThread {
            statusCard.setCardBackgroundColor("#DC3545".toColorInt()) // Danger Red
            statusEmoji.text = "❌"
            statusText.text = message
        }
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

    private fun hexToString(hex: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val str = hex.substring(i, i + 2)
            output.append(str.toInt(16).toChar())
            i += 2
        }
        return output.toString()
    }
}