package com.example.cardscanner

import android.content.Context
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class ScannerFragment : Fragment(), NfcAdapter.ReaderCallback {

    private lateinit var tlvContainer: LinearLayout
    private lateinit var lblHexAid: TextView
    private lateinit var scanStatusCard: MaterialCardView
    private lateinit var scanEmoji: TextView
    private lateinit var scanStatusText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_scanner, container, false)
        tlvContainer = view.findViewById(R.id.tlvContainer)
        lblHexAid = view.findViewById(R.id.lblHexAid)
        scanStatusCard = view.findViewById(R.id.scanStatusCard)
        scanEmoji = view.findViewById(R.id.scanEmoji)
        scanStatusText = view.findViewById(R.id.scanStatusText)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Direct safety initialization hook: arms the radar the second the page comes into view
        armScannerHardware()
    }

    fun armScannerHardware() {
        val act = activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(act)
        val opts = Bundle()
        adapter?.enableReaderMode(
            act, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            opts
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 4000

            activity?.runOnUiThread {
                scanStatusCard.setCardBackgroundColor(Color.parseColor("#EAB308")) // Yellow
                scanEmoji.text = "🔄"
                scanStatusText.text = "PARSING DATA DO NOT MOVE CARD..."
            }

            val result = EmvCardReaderEngine(requireContext(), isoDep).executeDeepScrape()
            if (result != null) {
                val prefs = requireActivity().getSharedPreferences(EmvStorage.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()
                editor.putString("HEX_AID", result.hexAid)

                val leafTags = result.tagsMap.filterKeys { !EmvTlvRules.isConstructed(it) }
                for ((tag, value) in leafTags) {
                    editor.putString("TAG_$tag", value)
                }
                for ((key, value) in result.apduPayloads) {
                    editor.putString(key, value)
                }
                editor.commit()

                val emvDataList = ArrayList<TagItem>()
                for ((tag, value) in leafTags.entries.sortedBy { it.key }) {
                    emvDataList.add(TagItem(tag, EmvDictionary.getDescription(tag), value))
                }

                activity?.runOnUiThread {
                    scanStatusCard.setCardBackgroundColor(Color.parseColor("#16A34A")) // Success Green
                    scanEmoji.text = "✅"
                    scanStatusText.text = "CARD DECODED SUCCESSFULLY!"
                    lblHexAid.text = "0x${result.hexAid}"

                    // Dynamically build and inject beautiful high-contrast UI view rows
                    tlvContainer.removeAllViews()
                    for (item in emvDataList) {
                        val cardView = LayoutInflater.from(context).inflate(R.layout.item_tlv_row, tlvContainer, false)

                        val txtTagCode = cardView.findViewById<TextView>(R.id.txtTagCode)
                        val txtTagName = cardView.findViewById<TextView>(R.id.txtTagName)
                        val txtTagValue = cardView.findViewById<TextView>(R.id.txtTagValue)

                        txtTagCode.text = "TAG [${item.code}]"
                        txtTagName.text = item.description
                        txtTagValue.text = item.value

                        tlvContainer.addView(cardView)
                    }
                }
            }
        } catch (e: Exception) {
            activity?.runOnUiThread {
                scanStatusCard.setCardBackgroundColor(Color.parseColor("#DC2626"))
                scanEmoji.text = "❌"
                scanStatusText.text = "COMMUNICATION BREAK. TRY AGAIN."
            }
        } finally {
            try { isoDep.close() } catch (ignored: Exception) {}
        }
    }
}

// Lightweight custom layout structure container definition
data class TagItem(val code: String, val description: String, val value: String)

object EmvTlvRules {
    private val constructedTags = setOf("6F", "70", "77", "A5", "61", "BF0C")

    fun isConstructed(tag: String): Boolean = tag.uppercase(Locale.US) in constructedTags
}
