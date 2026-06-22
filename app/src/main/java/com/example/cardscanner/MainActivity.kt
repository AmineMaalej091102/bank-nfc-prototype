package com.example.cardscanner

import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.devnied.emvnfccard.parser.EmvTemplate
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tlvContainer: LinearLayout
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusEmoji: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tlvContainer = findViewById(R.id.tlvContainer)
        statusCard = findViewById(R.id.statusCard)
        statusEmoji = findViewById(R.id.statusEmoji)
        statusText = findViewById(R.id.statusText)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        val options = Bundle()
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

    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 5000

            runOnUiThread {
                statusCard.setCardBackgroundColor(Color.parseColor("#EAB308"))
                statusEmoji.text = "🔄"
                statusText.text = "EXTRACTING EXTENDED TAG TREES..."
            }

            val provider = NfcProvider(isoDep)
            val config = EmvTemplate.Config().setContactLess(true).setReadAllAids(true)
            val parser = EmvTemplate.Builder().setProvider(provider).setConfig(config).build()

            val cardData = parser.readEmvCard()
            if (cardData != null) {

                val masterTagsMap = mutableMapOf<String, String>()

                val primaryApp = cardData.applications?.firstOrNull()
                val hexAid = primaryApp?.aid?.joinToString("") { String.format("%02X", it) } ?: "A0000000031010"

                masterTagsMap["4F"] = hexAid
                cardData.cardNumber?.let { masterTagsMap["5A"] = it.replace(" ", "") }

                val firstName = cardData.holderFirstname ?: ""
                val lastName = cardData.holderLastname ?: ""
                if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                    val fullHexName = "$firstName $lastName".trim().toByteArray(Charsets.US_ASCII).joinToString("") { String.format("%02X", it) }
                    masterTagsMap["5F20"] = fullHexName
                }

                // Deep scrape raw record files directly from physical storage slots
                for (sfi in 1..4) {
                    for (record in 1..12) {
                        val p2 = (sfi shl 3) or 4
                        val readRecordCmd = byteArrayOf(0x00.toByte(), 0xB2.toByte(), record.toByte(), p2.toByte(), 0x00.toByte())
                        try {
                            val recordResponse = isoDep.transceive(readRecordCmd)
                            val hexResponse = byteArrayToHexString(recordResponse)
                            if (hexResponse.endsWith("9000") && hexResponse.length > 4) {
                                parseRecursiveTLV(hexResponse.substring(0, hexResponse.length - 4), masterTagsMap)
                            }
                        } catch (ignored: Exception) {}
                    }
                }

                val emvDictionary = mapOf(
                    "4F" to "Application Identifier (AID)",
                    "5A" to "Primary Account Number (PAN)",
                    "5F20" to "Cardholder Name String",
                    "50" to "Application Label",
                    "5F24" to "Application Expiration Date",
                    "82" to "Application Interchange Profile (AIP)",
                    "94" to "Application File Locator (AFL)",
                    "8C" to "Card Risk Management Data Object List 1 (CDOL1)",
                    "8D" to "Card Risk Management Data Object List 2 (CDOL2)",
                    "9F07" to "Application Usage Control (AUC)",
                    "9F0D" to "Issuer Action Code - Default",
                    "9F0E" to "Issuer Action Code - Denial",
                    "9F0F" to "Issuer Action Code - Online",
                    "9F10" to "Issuer Application Data (IAD)",
                    "9F26" to "Application Cryptogram (AC)",
                    "9F27" to "Cryptogram Information Data (CID)",
                    "9F36" to "Application Transaction Counter (ATC)",
                    "9F37" to "Unpredictable Number (UN)",
                    "9F4A" to "Static Data Authentication Tag List",
                    "9F34" to "Cardholder Verification Method (CVM) Results",
                    "5F25" to "Application Effective Date",
                    "5F28" to "Issuer Country Code",
                    "5F2A" to "Transaction Currency Code",
                    "9F08" to "Application Version Number"
                )

                val decodedItems = ArrayList<TagDetail>()
                masterTagsMap.forEach { (tag, hexValue) ->
                    val cleanTag = tag.uppercase(Locale.US)
                    val nameDescriptor = emvDictionary[cleanTag] ?: "Proprietary Chip Data Block"

                    val printableValue = if (cleanTag == "5F20" || cleanTag == "50") {
                        hexToAsciiString(hexValue) + " ($hexValue)"
                    } else {
                        hexValue.uppercase(Locale.US)
                    }

                    decodedItems.add(TagDetail(cleanTag, nameDescriptor, printableValue))
                }

                runOnUiThread {
                    statusCard.setCardBackgroundColor(Color.parseColor("#16A34A"))
                    statusEmoji.text = "✅"
                    statusText.text = "DECODED EXTENDED DATA TREE SUCCESSFULLY"

                    tlvContainer.removeAllViews()
                    for (item in decodedItems) {
                        val rowView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tlv_row, tlvContainer, false)
                        rowView.findViewById<TextView>(R.id.txtTagCode).text = "TAG [${item.code}]"
                        rowView.findViewById<TextView>(R.id.txtTagName).text = item.name
                        rowView.findViewById<TextView>(R.id.txtTagValue).text = item.value
                        tlvContainer.addView(rowView)
                    }
                }
            } else {
                runOnUiThread {
                    statusCard.setCardBackgroundColor(Color.parseColor("#DC2626"))
                    statusText.text = "CHIP DATA READING ATTEMPT FAIL"
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusCard.setCardBackgroundColor(Color.parseColor("#DC2626"))
                statusEmoji.text = "❌"
                statusText.text = "READ ABORTED. KEEP CARD STILL."
            }
        } finally {
            try { isoDep.close() } catch (ignored: Exception) {}
        }
    }

    private fun parseRecursiveTLV(hex: String, map: MutableMap<String, String>) {
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
                    length = hex.substring(i, i + numSubsequentBytes * 2).toInt(16)
                    i += numSubsequentBytes * 2
                }
            }

            if (i + length * 2 > hex.length) break
            val value = hex.substring(i, i + length * 2)
            map[tag.uppercase(Locale.US)] = value.uppercase(Locale.US)

            if (tag.equals("70", true) || tag.equals("77", true) || tag.equals("61", true) || tag.equals("A5", true)) {
                parseRecursiveTLV(value, map)
            }
            i += length * 2
        }
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    private fun hexToAsciiString(hex: String): String {
        val output = java.lang.StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            try {
                val charCode = hex.substring(i, i + 2).toInt(16)
                if (charCode in 32..126) {
                    output.append(charCode.toChar())
                }
            } catch (e: Exception) {}
            i += 2
        }
        return output.toString()
    }
} // Closes MainActivity class cleanly

data class TagDetail(val code: String, val name: String, val value: String)
