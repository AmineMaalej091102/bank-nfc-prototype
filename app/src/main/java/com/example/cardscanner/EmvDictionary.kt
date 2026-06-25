package com.example.cardscanner

import java.util.Locale

object EmvDictionary {
    private val tagDescriptions = mapOf(
        "4F" to "Application Identifier (AID) - Card shadow Profile",
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
        "9F37" to "Unpredictable Number",
        "9F42" to "Application Currency Code",
        "84" to "Dedicated File (DF) Name",
        "9F10" to "Issuer Application Data (IAD)",
        "5F28" to "Issuer Country Code",
        "42" to "Issuer Identification Number (IIN)",
        "5F2D" to "Language Preference",
        "9F38" to "Processing Options Data Object List (PDOL)",
        "88" to "Short File Identifier (SFI)",
        "57" to "Track 2 Equivalent Data"
    )

    fun getDescription(tag: String): String {
        return tagDescriptions[tag.uppercase(Locale.US)] ?: "Scraped Complementary Segment"
    }
}
