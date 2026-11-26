package com.example.meshkids

import android.content.Context
import java.security.MessageDigest

class ParentPinManager(context: Context) {
    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12

        /** Must match what ParentPinDialog accepts, otherwise the admin locks themselves out. */
        fun isValidPin(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
    }

    private val prefs = context.getSharedPreferences("meshkids_security", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("pin_hash")) {
            setPin("1234")
        }
    }

    fun verifyPin(inputPin: String): Boolean {
        val savedHash = prefs.getString("pin_hash", null) ?: return false
        return hashPin(inputPin) == savedHash
    }

    /** Returns false (and keeps the old PIN) if [newPin] is not 4–12 digits. */
    fun setPin(newPin: String): Boolean {
        if (!isValidPin(newPin)) return false
        prefs.edit().putString("pin_hash", hashPin(newPin)).apply()
        return true
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
