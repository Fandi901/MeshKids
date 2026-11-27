package com.example.meshkids

import android.content.Context

class ParentPinManager(context: Context) {
    private val prefs = context.getSharedPreferences("meshkids_pin_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN = "parent_pin"
        private const val DEFAULT_PIN = "1234"

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12

        /** Must match what ParentPinDialog accepts, otherwise the admin locks themselves out. */
        fun isValidPin(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
    }

    fun verifyPin(entered: String): Boolean {
        val saved = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        return entered.trim() == saved
    }

    /** Returns false (and keeps the old PIN) if [newPin] is not 4–12 digits. */
    fun setPin(newPin: String): Boolean {
        val cleaned = newPin.trim()
        if (!isValidPin(cleaned)) return false
        prefs.edit().putString(KEY_PIN, cleaned).apply()
        return true
    }
}
