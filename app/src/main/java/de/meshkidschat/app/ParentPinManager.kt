package de.meshkidschat.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ParentPinManager(context: Context) {
    private val prefs = context.getSharedPreferences("meshkids_pin_prefs", Context.MODE_PRIVATE)

    data class PinVerifyResult(
        val success: Boolean,
        val remainingLockoutSeconds: Long = 0L,
        val failedAttempts: Int = 0
    )

    companion object {
        private const val KEY_PIN_HASH = "parent_pin_pbkdf2_hash"
        private const val KEY_PIN_SALT = "parent_pin_pbkdf2_salt"
        private const val KEY_PIN_ITERATIONS = "parent_pin_pbkdf2_iter"
        private const val KEY_LEGACY_PIN = "parent_pin"
        private const val KEY_FAILED_ATTEMPTS = "parent_pin_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL_MS = "parent_pin_lockout_until_ms"

        private const val DEFAULT_ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val KEY_LENGTH_BITS = 256
        private const val LOCKOUT_THRESHOLD = 5

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12

        fun isValidPin(pin: String): Boolean {
            return pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
        }

        private fun computeLockoutDurationMs(failedAttemptsAfterThreshold: Int): Long {
            return when (failedAttemptsAfterThreshold) {
                1 -> 30_000L
                2 -> 60_000L
                3 -> 300_000L
                4 -> 900_000L
                else -> 1_800_000L
            }
        }

        private fun deriveHashHex(pin: String, saltHex: String, iterations: Int): String? {
            return try {
                val saltBytes = hexToBytes(saltHex) ?: return null
                val spec = PBEKeySpec(pin.toCharArray(), saltBytes, iterations, KEY_LENGTH_BITS)
                val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val hashBytes = skf.generateSecret(spec).encoded
                spec.clearPassword()
                bytesToHex(hashBytes)
            } catch (_: Exception) {
                null
            }
        }

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { idx ->
                    hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    init {
        if (prefs.contains(KEY_LEGACY_PIN)) {
            prefs.edit().remove(KEY_LEGACY_PIN).apply()
        }
    }

    fun isPinConfigured(): Boolean {
        val hash = prefs.getString(KEY_PIN_HASH, null)
        val salt = prefs.getString(KEY_PIN_SALT, null)
        return !hash.isNullOrBlank() && !salt.isNullOrBlank()
    }

    fun remainingLockoutSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        val diffMs = lockoutUntil - nowMs
        return if (diffMs > 0L) ((diffMs + 999L) / 1000L) else 0L
    }

    fun verifyPinDetailed(entered: String): PinVerifyResult {
        val nowMs = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        if (lockoutUntil > nowMs) {
            val remSec = ((lockoutUntil - nowMs) + 999L) / 1000L
            return PinVerifyResult(
                success = false,
                remainingLockoutSeconds = remSec,
                failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
            )
        }

        if (!isPinConfigured()) {
            return PinVerifyResult(success = false, remainingLockoutSeconds = 0L, failedAttempts = 0)
        }

        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return PinVerifyResult(false)
        val savedSalt = prefs.getString(KEY_PIN_SALT, null) ?: return PinVerifyResult(false)
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, DEFAULT_ITERATIONS).coerceAtLeast(10_000)

        val candidateHash = if (isValidPin(entered)) {
            deriveHashHex(entered, savedSalt, iterations)
        } else {
            null
        }

        val isMatch = candidateHash != null &&
            MessageDigest.isEqual(
                candidateHash.toByteArray(Charsets.UTF_8),
                savedHash.toByteArray(Charsets.UTF_8)
            )

        return if (isMatch) {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
                .apply()
            PinVerifyResult(success = true, remainingLockoutSeconds = 0L, failedAttempts = 0)
        } else {
            val nextAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            var remSec = 0L
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, nextAttempts)
            if (nextAttempts >= LOCKOUT_THRESHOLD) {
                val extraStep = nextAttempts - LOCKOUT_THRESHOLD + 1
                val delayMs = computeLockoutDurationMs(extraStep)
                val newLockoutUntil = nowMs + delayMs
                editor.putLong(KEY_LOCKOUT_UNTIL_MS, newLockoutUntil)
                remSec = ((newLockoutUntil - nowMs) + 999L) / 1000L
            }
            editor.apply()
            PinVerifyResult(
                success = false,
                remainingLockoutSeconds = remSec,
                failedAttempts = nextAttempts
            )
        }
    }

    fun verifyPin(entered: String): Boolean = verifyPinDetailed(entered).success

    fun setPin(newPin: String): Boolean {
        if (!isValidPin(newPin)) return false
        val saltBytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(saltBytes)
        val saltHex = bytesToHex(saltBytes)
        val hashHex = deriveHashHex(newPin, saltHex, DEFAULT_ITERATIONS) ?: return false

        prefs.edit()
            .putString(KEY_PIN_SALT, saltHex)
            .putString(KEY_PIN_HASH, hashHex)
            .putInt(KEY_PIN_ITERATIONS, DEFAULT_ITERATIONS)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .remove(KEY_LEGACY_PIN)
            .apply()
        return true
    }
}
