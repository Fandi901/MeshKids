package com.example.meshkids

import android.content.Context

class ParentPinManager(context: Context) {
    private val prefs = context.getSharedPreferences("meshkids_pin_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN = "parent_pin"
        private const val DEFAULT_PIN = "1234"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12

        fun isValidPin(pin: String): Boolean {
            return pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
        }
    }

    fun verifyPin(entered: String): Boolean {
        if (!isValidPin(entered)) return false
        val saved = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        return entered == saved
    }

    fun setPin(newPin: String): Boolean {
        if (!isValidPin(newPin)) return false
        prefs.edit().putString(KEY_PIN, newPin).apply()
        return true
    }
}
