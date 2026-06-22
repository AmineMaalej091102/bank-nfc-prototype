package com.example.cardscanner

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class EmulatorFragment : Fragment() {

    private lateinit var txtActiveAid: TextView
    private lateinit var btnWipeData: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_emulator, container, false)
        txtActiveAid = view.findViewById(R.id.txtActiveAid)
        btnWipeData = view.findViewById(R.id.btnWipeData)

        refreshDisplayData()

        btnWipeData.setOnClickListener {
            val prefs = requireActivity().getSharedPreferences("EMV_Payload_Cache", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            refreshDisplayData()
        }

        return view
    }

    private fun refreshDisplayData() {
        val prefs = requireActivity().getSharedPreferences("EMV_Payload_Cache", Context.MODE_PRIVATE)
        val savedAid = prefs.getString("HEX_AID", null)
        val savedPan = prefs.getString("TAG_5A", null)

        if (savedAid != null) {
            txtActiveAid.text = "ACTIVE CLONE AID: 0x$savedAid\nPAN LOADED: $savedPan\n\n📶 Tap against SoftPOS terminal now!"
        } else {
            txtActiveAid.text = "⚠️ NO CARD INSTANCE LOADED\nGo to Tab 1 to scan a physical card first."
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDisplayData()
    }
}
