package com.example.cardscanner

import android.content.Context
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
                statusText.text = "DECODING EXTENDED CHIP MATRIX..."
            }

            val provider = NfcProvider(isoDep)
            val config = EmvTemplate.Config().setContactLess(true).setReadAllAids(true)
            val parser = EmvTemplate.Builder().setProvider(provider).setConfig(config).build()

            val cardData = try {
                parser.readEmvCard()
            } catch (e: Exception) {
                null
            }

            if (cardData != null) {
                val masterTagsMap = mutableMapOf<String, String>()

                val primaryApp = cardData.applications?.firstOrNull()
                val hexAid = primaryApp?.aid?.joinToString("") { String.format("%02X", it) } ?: "A0000000041010"

                masterTagsMap["4F"] = hexAid

                // FIXED: Query library's Track 2 object properties correctly using 'cardNumber'
                val rawPan = cardData.cardNumber
                if (!rawPan.isNullOrEmpty() && !rawPan.contains("•")) {
                    masterTagsMap["5A"] = rawPan.replace(" ", "")
                } else {
                    val track2 = cardData.track2
                    if (track2 != null && !track2.cardNumber.isNullOrEmpty()) {
                        masterTagsMap["5A"] = track2.cardNumber.replace(" ", "")
                    } else {
                        masterTagsMap["5A"] = "••••••••••••••••"
                    }
                }

                val firstName = cardData.holderFirstname ?: ""
                val lastName = cardData.holderLastname ?: ""
                val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) "$firstName $lastName".trim() else "Valued Customer"

                try {
                    if (fullName != "Valued Customer") {
                        val fullHexName = fullName.toByteArray(Charsets.US_ASCII).joinToString("") { String.format("%02X", it) }
                        masterTagsMap["5F20"] = fullHexName
                    }
                } catch (e: Exception) {}

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
                        } catch (e: Exception) {}
                    }
                }

                val prefs = getSharedPreferences("EMV_CLONE_STORAGE", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()
                editor.putString("HEX_AID", hexAid)
                editor.putString("TAG_5A", masterTagsMap["5A"] ?: "")
                editor.putString("TAG_5F24", "291231")

                masterTagsMap.forEach { (tagCode, hexValue) ->
                    editor.putString("TAG_${tagCode.uppercase(Locale.US)}", hexValue.uppercase(Locale.US))
                }
                editor.apply()

                val emvDictionary = mapOf(
                    "5A" to "Primary Account Number (PAN)",
                    "5F20" to "Cardholder Name String",
                    "50" to "Application Label / Network Scheme",
                    "5F24" to "Application Expiration Date",
                    "82" to "Application Interchange Profile (AIP)",
                    "94" to "Application File Locator (AFL)",
                    "8C" to "Card Risk Management Data Object List 1 (CDOL1)",
                    "8D" to "Card Risk Management Data Object List 2 (CDOL2)",
                    "9F36" to "Application Transaction Counter (ATC)",
                    "9F37" to "Unpredictable Number (UN)",
                    "9F10" to "Issuer Application Data (IAD)"
                )

                val decodedItems = ArrayList<TagDetail>()
                masterTagsMap.forEach { (tagCode, hexValue) ->
                    if (tagCode != "4F") {
                        val descriptor = emvDictionary[tagCode.uppercase(Locale.US)] ?: "Proprietary Data Segment"
                        decodedItems.add(TagDetail(tagCode.uppercase(Locale.US), descriptor, hexValue.uppercase(Locale.US)))
                    }
                }

                runOnUiThread {
                    try {
                        statusCard.setCardBackgroundColor(Color.parseColor("#16A34A"))
                        statusEmoji.text = "✅"
                        statusText.text = "CHIP DECODED SUCCESSFULLY"

                        val formattedAid = hexAid.uppercase(Locale.US).chunked(2).joinToString(":")
                        val txtFirstCardValue = findViewById<TextView>(R.id.txtFirstCardValue)
                        txtFirstCardValue?.text = formattedAid

                        tlvContainer.removeAllViews()
                        for (item in decodedItems) {
                            val rowView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tlv_row, tlvContainer, false)
                            rowView.findViewById<TextView>(R.id.txtTagCode).text = "TAG [${item.code}]"
                            rowView.findViewById<TextView>(R.id.txtTagName).text = item.name
                            rowView.findViewById<TextView>(R.id.txtTagValue).text = item.value
                            tlvContainer.addView(rowView)
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusCard.setCardBackgroundColor(Color.parseColor("#DC2626"))
                statusEmoji.text = "❌"
                statusText.text = "READ ABORTED. KEEP CARD STILL."
            }
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
        }
    }

    // FIXED: Built the parsing utilities directly into this activity class file
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
                    val lengthValueStr = hex.substring(i, i + numSubsequentBytes * 2)
                    length = lengthValueStr.toInt(16)
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
}

data class TagDetail(val code: String, val name: String, val value: String)
