package com.example.meshkids

import android.content.Context
import java.security.MessageDigest

class ParentPinManager(context: Context) {
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

    fun setPin(newPin: String) {
        if (newPin.length >= 4) {
            prefs.edit().putString("pin_hash", hashPin(newPin)).apply()
        }
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
