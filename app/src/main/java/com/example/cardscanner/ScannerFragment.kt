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

            val provider = NfcProvider(isoDep)
            val config = com.github.devnied.emvnfccard.parser.EmvTemplate.Config()
                .setContactLess(true).setReadAllAids(true)
            val parser = com.github.devnied.emvnfccard.parser.EmvTemplate.Builder()
                .setProvider(provider).setConfig(config).build()

            val cardData = parser.readEmvCard()
            if (cardData != null) {
                val primaryApp = cardData.applications?.firstOrNull()
                val hexAid = primaryApp?.aid?.joinToString("") { String.format("%02X", it) } ?: "A0000000041010"
                val label = primaryApp?.applicationLabel ?: "Contactless Card"

                // Construct dictionary data map for non-literate presentation format
                val emvDataList = ArrayList<TagItem>()
                emvDataList.add(TagItem("5A", "Account Card Number", cardData.cardNumber ?: "Unknown"))
                // Safely capture the separate text properties from the library mapping classes
                val firstName = cardData.holderFirstname ?: ""
                val lastName = cardData.holderLastname ?: ""

                // Combine them or provide a high-contrast fallback string if empty
                val fullCardholderName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                    "$firstName $lastName".trim()
                } else {
                    "Valued Customer"
                }

                // Inject the corrected string value safely back into Tag 5F20 layout row
                emvDataList.add(TagItem("5F20", "Cardholder Name", fullCardholderName))
                emvDataList.add(TagItem("5F24", "Expiration Date", cardData.expireDate?.let { java.text.SimpleDateFormat("MM/yy").format(it) } ?: "••/••"))
                emvDataList.add(TagItem("4F", "Application Identifier", hexAid))
                emvDataList.add(TagItem("50", "Card Network Label", label))
                emvDataList.add(TagItem("82", "Application Interchange Profile (AIP)", "0080 (Contactless Standard Protocol Optimized)"))
                emvDataList.add(TagItem("9F06", "Terminal Authorized AID Application", hexAid))

                // Cache elements to memory for immediate HCE Emulation handling
                val prefs = requireActivity().getSharedPreferences("EMV_Payload_Cache", Context.MODE_PRIVATE)
                prefs.edit().putString("HEX_AID", hexAid).putString("TAG_5A", cardData.cardNumber?.replace(" ", "") ?: "").apply()

                activity?.runOnUiThread {
                    scanStatusCard.setCardBackgroundColor(Color.parseColor("#16A34A")) // Success Green
                    scanEmoji.text = "✅"
                    scanStatusText.text = "CARD DECODED SUCCESSFULLY!"
                    lblHexAid.text = "0x$hexAid"

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
