package com.example.cardscanner

import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText as EditText
import java.util.Locale

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tlvContainer: LinearLayout
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusEmoji: TextView
    private lateinit var statusText: TextView
    private lateinit var searchFilterBar: EditText
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var layoutTagsContainer: LinearLayout
    private lateinit var layoutRawContainer: View
    private lateinit var txtRawHexDump: TextView

    private val masterDecodedItemsList = ArrayList<TagDetail>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tlvContainer = findViewById(R.id.tlvContainer)
        statusCard = findViewById(R.id.statusCard)
        statusEmoji = findViewById(R.id.statusEmoji)
        statusText = findViewById(R.id.statusText)
        searchFilterBar = findViewById(R.id.searchFilterBar)
        toggleGroup = findViewById(R.id.toggleGroup)
        layoutTagsContainer = findViewById(R.id.layoutTagsContainer)
        layoutRawContainer = findViewById(R.id.layoutRawContainer)
        txtRawHexDump = findViewById(R.id.txtRawHexDump)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val isTags = checkedId == R.id.btnModeTags
                layoutTagsContainer.visibility = if (isTags) View.VISIBLE else View.GONE
                layoutRawContainer.visibility = if (isTags) View.GONE else View.VISIBLE

                // UX STATE COLOR FIX: When switching tabs, dynamically reset the top card back
                // to its default armed slate gray configuration to clear old state colors
                statusCard.setCardBackgroundColor(Color.parseColor("#1E293B"))
                statusEmoji.text = "🛰️"
                statusText.text = "ANTENNA ARMED: HOLD CARD TO BACK"
            }
        }

        searchFilterBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString()
                searchFilterBar.setHint(if (input.isNotEmpty()) null else "🔍 Search by Tag ID or Name (e.g. 5A, PAN, AID)...")
                applyRealTimeTagFilter(input)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val options = Bundle()
        nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            runOnUiThread {
                statusCard.setCardBackgroundColor(Color.parseColor("#EAB308"))
                statusEmoji.text = "\uD83D\uDD04"
                statusText.text = "DECODING CORE MULTI-TAG TREES..."
            }

            val result = EmvCardReaderEngine(isoDep).executeDeepScrape()
            if (result != null) {
                synchronized(masterDecodedItemsList) {
                    masterDecodedItemsList.clear()
                    result.tagsMap.forEach { (tagCode, hexValue) ->
                        val cleanTag = tagCode.uppercase(Locale.US)
                        val printableValue = if (cleanTag == "4F") HexUtils.formatAidToColonHex(hexValue) else hexValue.uppercase(Locale.US)
                        masterDecodedItemsList.add(TagDetail(cleanTag, EmvDictionary.getDescription(cleanTag), printableValue))
                    }
                    masterDecodedItemsList.sortWith(Comparator { o1, o2 ->
                        if (o1.code == "4F") -1 else if (o2.code == "4F") 1 else o1.code.compareTo(o2.code)
                    })
                }

                runOnUiThread {
                    statusCard.setCardBackgroundColor(Color.parseColor("#16A34A"))
                    statusEmoji.text = "✅"
                    statusText.text = "CHIP DECODED SUCCESSFULLY"
                    txtRawHexDump.text = result.rawHexDump.ifEmpty { "No streaming bytes captured." }
                    applyRealTimeTagFilter(searchFilterBar.text.toString())
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusCard.setCardBackgroundColor(Color.parseColor("#DC2626"))
                statusEmoji.text = "❌"
                statusText.text = "CHIP READ FAILED. TRY AGAIN."
            }
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
        }
    }

    private fun applyRealTimeTagFilter(queryText: String) {
        val cleanQuery = queryText.uppercase(Locale.US).trim()
        tlvContainer.removeAllViews()

        synchronized(masterDecodedItemsList) {
            for (item in masterDecodedItemsList) {
                if (cleanQuery.isEmpty() || item.code.contains(cleanQuery) || item.name.uppercase(Locale.US).contains(cleanQuery)) {
                    val rowView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tlv_row, tlvContainer, false)
                    rowView.findViewById<TextView>(R.id.txtTagCode).text = "TAG [${item.code}]"
                    rowView.findViewById<TextView>(R.id.txtTagName).text = item.name

                    val txtTagValue = rowView.findViewById<TextView>(R.id.txtTagValue)
                    txtTagValue.text = item.value

                    // UX TEXT SIZE FIX: Standardize font to 12f so long colon-hex strings remain single-line
                    // and fit perfectly inside their bounding box layout bounds
                    if (item.code == "4F") {
                        txtTagValue.setSingleLine(true)
                        txtTagValue.textSize = 14f
                    }
                    tlvContainer.addView(rowView)
                }
            }
        }
    }
}
