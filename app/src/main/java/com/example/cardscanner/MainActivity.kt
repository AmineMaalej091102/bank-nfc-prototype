package com.example.cardscanner

import android.annotation.SuppressLint
import android.graphics.Color
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
            isoDep.timeout = 4000
            updateUiState("#EAB308", "🔄", "Reading Chip Data Safely...")

            // 1. Link our custom physical channel interface
            val provider = NfcProvider(isoDep)

            // 2. Configure the official EMV Template properties
            val config = com.github.devnied.emvnfccard.parser.EmvTemplate.Config()
                .setContactLess(true)
                .setReadAllAids(true)
                .setReadTransactions(true)
                .setRemoveDefaultParsers(false)
                .setReadAt(true)

            // 3. Initialize using the correct official Builder engine matching your library version
            val parser = com.github.devnied.emvnfccard.parser.EmvTemplate.Builder()
                .setProvider(provider)
                .setConfig(config)
                .build()

            // 4. Execute the dynamic card extraction task
            val cardData = parser.readEmvCard()

            if (cardData != null) {
                // Java library properties are cleanly exposed via getters/setters
                // Safely grab the first primary application profile found on the chip
                val primaryApp = cardData.applications?.firstOrNull()

                // Converts the raw memory byte array into a clean hex string
                val rawAidBytes = primaryApp?.aid
                val aidStr = if (rawAidBytes != null) {
                    rawAidBytes.joinToString("") { String.format("%02X", it) }
                } else {
                    "N/A"
                }

                val networkLabel = cardData.type?.name ?: "Contactless Card"
                val panNumber = cardData.cardNumber ?: "•••• •••• •••• ••••"

                val dateObject = cardData.expireDate
                val formattedDate = if (dateObject != null) {
                    val sdf = java.text.SimpleDateFormat("MM/yy", java.util.Locale.US)
                    sdf.format(dateObject)
                } else {
                    "••/••"
                }

                runOnUiThread {
                    this@MainActivity.statusCard.setCardBackgroundColor(Color.parseColor("#16A34A"))
                    this@MainActivity.statusEmoji.text = "✅"
                    this@MainActivity.statusText.text = "Card Parsed Successfully!"

                    this@MainActivity.txtAid.text = "Application ID (AID): $aidStr"
                    this@MainActivity.txtLabel.text = "Network: $networkLabel"

                    val displayBuilder = StringBuilder()
                    displayBuilder.append("💳 ACCOUNT INFORMATION (PARSED)\n")
                    displayBuilder.append("├─ PAN: ").append(panNumber).append("\n")
                    displayBuilder.append("└─ EXPIRY: ").append(formattedDate).append("\n\n")

                    displayBuilder.append("⚙️ EMV TECHNICAL PARAMETERS\n")
                    displayBuilder.append("├─ Card Scheme: ").append(cardData.type?.let { it.name } ?: "Unknown").append("\n")
                    displayBuilder.append("├─ Application Label: ").append(primaryApp?.applicationLabel ?: "None").append("\n")
                    displayBuilder.append("└─ BIC: ").append(cardData.bic ?: "Not Provided").append("\n")

                    this@MainActivity.txtTlvOutput.text = displayBuilder.toString()
                }
            } else {
                updateUiState("#DC2626", "❌", "Failed to parse EMV structure.")
            }
        } catch (e: Exception) {
            updateUiState("#DC2626", "❌", "Card moved too fast or communication failed.")
        } finally {
            try { isoDep.close() } catch (ignored: Exception) {}
        }
    }



    // Helper Function: Parses raw PDOL structures to calculate expected terminal input sizes
    private fun calculateDolLength(pdolHex: String): Int {
        var i = 0
        var totalLength = 0
        while (i < pdolHex.length) {
            var tag = pdolHex.substring(i, i + 2)
            i += 2
            val firstByte = tag.toInt(16)
            if ((firstByte and 0x1F) == 0x1F) {
                tag += pdolHex.substring(i, i + 2)
                i += 2
            }
            if (i >= pdolHex.length) break
            val lenStr = pdolHex.substring(i, i + 2)
            i += 2
            totalLength += lenStr.toInt(16)
        }
        return if (totalLength == 0) 4 else totalLength
    }



    private fun updateUiState(colorHex: String, emoji: String, prompt: String) {
        runOnUiThread {
            statusCard.setCardBackgroundColor(Color.parseColor(colorHex))
            statusEmoji.text = emoji
            statusText.text = prompt
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