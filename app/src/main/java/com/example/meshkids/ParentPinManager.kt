package com.example.meshkids

import android.content.Context

class ParentPinManager(context: Context) {
    private val prefs = context.getSharedPreferences("meshkids_pin_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN = "parent_pin"
        private const val DEFAULT_PIN = "1234"
    }

    fun verifyPin(entered: String): Boolean {
        val saved = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        return entered.trim() == saved
    }

    fun setPin(newPin: String) {
        val cleaned = newPin.trim()
        if (cleaned.length >= 4) {
            prefs.edit().putString(KEY_PIN, cleaned).apply()
        }
    }
}
