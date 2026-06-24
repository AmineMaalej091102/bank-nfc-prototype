package com.example.cardscanner

import android.content.Context
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import com.google.android.material.textfield.TextInputEditText as EditText
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
    private lateinit var searchFilterBar: EditText
    // Core Variable: Keeps a master copy of parsed tags to allow real-time UI filtering
    private val masterDecodedItemsList = ArrayList<TagDetail>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tlvContainer = findViewById(R.id.tlvContainer)
        statusCard = findViewById(R.id.statusCard)
        statusEmoji = findViewById(R.id.statusEmoji)
        statusText = findViewById(R.id.statusText)
        searchFilterBar = findViewById(R.id.searchFilterBar)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Bind text typing changes straight to our filter and instant-clear hint engine
        searchFilterBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val userInput = s.toString()

                if (userInput.isNotEmpty()) {
                    searchFilterBar.setHint(null)
                } else {
                    // Matches the exact high-visibility string parameter
                    searchFilterBar.setHint("🔍 Search by Tag ID or Name (e.g. 5A, PAN, AID)...")
                }

                applyRealTimeTagFilter(userInput)
            }
        })
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
                statusText.text = "DECODING CORE MULTI-TAG TREES..."
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

                // Resolve network parsing differences for track layers safely
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

                // Deep scan short records natively to collect hidden data segments
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

                // Execute dynamic terminal challenge phase to parse transaction cryptograms
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
                    val cryptoHex = byteArrayToHexString(cryptoResponse)
                    if (cryptoHex.endsWith("9000") && cryptoHex.length > 4) {
                        parseRecursiveTLV(cryptoHex.substring(0, cryptoHex.length - 4), masterTagsMap)
                    }
                } catch (e: Exception) {}

                // THE 27 TARGET FINANCIAL SYSTEM DESCRIPTOR DICTIONARY MAP
                val emvDictionary = mapOf(
                    "4F" to "Application Identifier (AID) - Card Primary Key",
                    "5A" to "Application Primary Account Number (PAN)",
                    "50" to "Application Label",
                    "5F24" to "Application Expiration Date",
                    "5F25" to "Application Effective Date",
                    "94" to "Application File Locator (AFL)",
                    "82" to "Application Interchange Profile (AIP)",
                    "5F34" to "Application PAN Sequence Number",
                    "87" to "Application Priority Indicator",
                    "9F36" to "Application Transaction Counter (ATC)",
                    "9F07" to "Application Usage Control (AUC)",
                    "9F08" to "Application Version Number",
                    "8C" to "Card Risk Management Data Object List 1 (CDOL1)",
                    "8D" to "Card Risk Management Data Object List 2 (CDOL2)",
                    "8E" to "Cardholder Verification Method (CVM) List",
                    "9F26" to "Application Request Cryptogram (ARQC Signature)",
                    "9F27" to "Cryptogram Information Data (CID Profile)",
                    "9F42" to "Application Currency Code",
                    "84" to "Dedicated File (DF) Name",
                    "9F4C" to "ICC Dynamic Number",
                    "9F10" to "Issuer Application Data (IAD)",
                    "5F28" to "Issuer Country Code",
                    "42" to "Issuer Identification Number (IIN)",
                    "5F2D" to "Language Preference",
                    "9F38" to "Processing Options Data Object List (PDOL)",
                    "88" to "Short File Identifier (SFI)",
                    "57" to "Track 2 Equivalent Data"
                )

                // =========================================================================
                // CORRECTED MAPPING BLOCK: Populates the global filter list natively
                // =========================================================================
                synchronized(masterDecodedItemsList) {
                    masterDecodedItemsList.clear() // Wipes data from old card runs to keep cache fresh

                    masterTagsMap.forEach { (tagCode, hexValue) ->
                        val cleanTag = tagCode.uppercase(Locale.US)
                        val descriptor = emvDictionary[cleanTag] ?: "Scraped Complementary Segment"

                        // FORMATS THE AID INTO A SCROLLABLE, POLISHED COLON-HEXADECIMAL FORM
                        val printableValue = if (cleanTag == "4F") {
                            hexValue.uppercase(Locale.US).chunked(2).joinToString(":")
                        } else {
                            hexValue.uppercase(Locale.US)
                        }

                        // Adds directly to the master tracking list so the search bar can see it
                        masterDecodedItemsList.add(TagDetail(cleanTag, descriptor, printableValue))
                    }

                    // Forces the AID card to float directly to the top of the scroll hierarchy array
                    masterDecodedItemsList.sortWith(Comparator { o1, o2 ->
                        if (o1.code == "4F") -1 else if (o2.code == "4F") 1 else o1.code.compareTo(o2.code)
                    })
                }


                runOnUiThread {
                    try {
                        statusCard.setCardBackgroundColor(Color.parseColor("#16A34A"))
                        statusEmoji.text = "✅"
                        statusText.text = "CHIP DECODED SUCCESSFULLY"

                        tlvContainer.removeAllViews()
                        for (item in masterDecodedItemsList) {
                            val rowView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tlv_row, tlvContainer, false)

                            val txtTagCode = rowView.findViewById<TextView>(R.id.txtTagCode)
                            val txtTagName = rowView.findViewById<TextView>(R.id.txtTagName)
                            val txtTagValue = rowView.findViewById<TextView>(R.id.txtTagValue)

                            txtTagCode.text = "TAG [${item.code}]"
                            txtTagName.text = item.name
                            txtTagValue.text = item.value

                            // UX Polish: Enforce absolute single-line presentation rules on your AID rows
                            if (item.code == "4F") {
                                txtTagValue.setSingleLine(true)
                                txtTagValue.textSize = 15f
                            }

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

    // Dynamic Filter Engine: Clears the container view rows and maps only the items matching the query text string
    private fun applyRealTimeTagFilter(queryText: String) {
        val cleanQuery = queryText.uppercase(Locale.US).trim()
        tlvContainer.removeAllViews()

        synchronized(masterDecodedItemsList) {
            for (item in masterDecodedItemsList) {
                // If query is empty, OR if the item code or its full descriptive name contains the query characters
                if (cleanQuery.isEmpty() ||
                    item.code.contains(cleanQuery) ||
                    item.name.uppercase(Locale.US).contains(cleanQuery)) {

                    val rowView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tlv_row, tlvContainer, false)
                    val txtTagCode = rowView.findViewById<TextView>(R.id.txtTagCode)
                    val txtTagName = rowView.findViewById<TextView>(R.id.txtTagName)
                    val txtTagValue = rowView.findViewById<TextView>(R.id.txtTagValue)

                    txtTagCode.text = "TAG [${item.code}]"
                    txtTagName.text = item.name
                    txtTagValue.text = item.value

                    if (item.code == "4F") {
                        txtTagValue.setSingleLine(true)
                        txtTagValue.textSize = 15f
                    }

                    tlvContainer.addView(rowView)
                }
            }
        }
    }

    private fun parseRecursiveTLV(hex: String, map: MutableMap<String, String>) {
        var i = 0
        while (i < hex.length - 2) {
            var tag = hex.substring(i, i + 2)
            i += 2

            val firstByteInt = tag.toInt(16)

            // EMV SPEC BITMASK RULE: If the first byte ends with 0x1F, it means the Tag ID is 2 bytes long
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

            // Handle multi-byte length fields (EMV specification extension rules)
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

            val upperTagKey = tag.uppercase(Locale.US)
            map[upperTagKey] = value.uppercase(Locale.US)

            // =========================================================================
            // CORE SUB-TAG DETECTOR MATRIX:
            // If the 6th bit of the first byte is 1 (firstByteInt AND 0x20 == 0x20),
            // OR if it matches standard known EMV folder templates (70, 77, A5, 61, BF0C),
            // it is a Constructed Tag. We break it open recursively to extract its sub-tags!
            // =========================================================================
            if ((firstByteInt and 0x20 == 0x20) ||
                upperTagKey == "70" || upperTagKey == "77" ||
                upperTagKey == "A5" || upperTagKey == "61" ||
                upperTagKey == "BF0C" || upperTagKey == "6F") {

                // Pass the inner hex value straight back into the loop to decode nested levels
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
