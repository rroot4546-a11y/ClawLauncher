package com.roox.clawlauncher.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

enum class LicenseState {
    CHECKING, VALID, EXPIRED, INVALID, REVOKED, NO_KEY, OFFLINE_GRACE, ERROR
}

data class LicenseStatus(
    val state: LicenseState = LicenseState.NO_KEY,
    val message: String = "",
    val expiresAt: Long = 0,
    val daysLeft: Int = 0,
    val plan: String = ""
)

class LicenseManager(private val context: Context) {
    private val TAG = "ClawLicense"
    private val prefs = context.getSharedPreferences("claw_license", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(LicenseStatus())
    val status: StateFlow<LicenseStatus> = _status

    // Your Firebase Cloud Function URL (set this after deployment)
    private val LICENSE_API = "https://us-central1-clawlauncher-license.cloudfunctions.net/api"

    // Offline grace period: 3 days
    private val OFFLINE_GRACE_MS = 3L * 24 * 60 * 60 * 1000

    // Check interval: every 6 hours
    val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    val savedKey: String get() = decrypt(prefs.getString("lk", "") ?: "")
    val isActivated: Boolean get() = savedKey.isNotBlank()

    // Get unique device fingerprint
    fun getDeviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val raw = "${androidId}|${Build.BRAND}|${Build.MODEL}|${Build.SERIAL}|${context.packageName}"
        return sha256(raw).take(32)
    }

    // Activate a license key
    suspend fun activate(key: String): LicenseStatus {
        _status.value = LicenseStatus(state = LicenseState.CHECKING, message = "Activating...")

        return withContext(Dispatchers.IO) {
            try {
                val result = apiCall("activate", mapOf(
                    "key" to key,
                    "deviceId" to getDeviceId(),
                    "deviceName" to "${Build.BRAND} ${Build.MODEL}",
                    "appVersion" to getAppVersion()
                ))

                if (result.optBoolean("success")) {
                    val expiresAt = result.optLong("expiresAt")
                    val plan = result.optString("plan", "monthly")

                    // Save encrypted
                    prefs.edit()
                        .putString("lk", encrypt(key))
                        .putLong("expires", expiresAt)
                        .putLong("lastCheck", System.currentTimeMillis())
                        .putString("plan", plan)
                        .putString("checksum", computeChecksum(key, expiresAt))
                        .apply()

                    val daysLeft = ((expiresAt - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    val status = LicenseStatus(
                        state = LicenseState.VALID,
                        message = "Activated! $daysLeft days remaining",
                        expiresAt = expiresAt,
                        daysLeft = daysLeft,
                        plan = plan
                    )
                    _status.value = status
                    status
                } else {
                    val error = result.optString("error", "Invalid license key")
                    val status = LicenseStatus(state = LicenseState.INVALID, message = error)
                    _status.value = status
                    status
                }
            } catch (e: Exception) {
                Log.e(TAG, "Activation failed: ${e.message}")
                val status = LicenseStatus(state = LicenseState.ERROR, message = "Connection error: ${e.message}")
                _status.value = status
                status
            }
        }
    }

    // Verify license (called periodically)
    suspend fun verify(): LicenseStatus {
        val key = savedKey
        if (key.isBlank()) {
            _status.value = LicenseStatus(state = LicenseState.NO_KEY, message = "No license key")
            return _status.value
        }

        // Tamper check: verify stored checksum
        val storedChecksum = prefs.getString("checksum", "") ?: ""
        val storedExpires = prefs.getLong("expires", 0)
        if (storedChecksum != computeChecksum(key, storedExpires)) {
            Log.w(TAG, "Tamper detected: checksum mismatch")
            clearLicense()
            _status.value = LicenseStatus(state = LicenseState.INVALID, message = "License tampered")
            return _status.value
        }

        // Try online verification
        return withContext(Dispatchers.IO) {
            try {
                val result = apiCall("verify", mapOf(
                    "key" to key,
                    "deviceId" to getDeviceId()
                ))

                if (result.optBoolean("valid")) {
                    val expiresAt = result.optLong("expiresAt", storedExpires)
                    val plan = result.optString("plan", prefs.getString("plan", "monthly") ?: "monthly")
                    val daysLeft = ((expiresAt - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()

                    prefs.edit()
                        .putLong("expires", expiresAt)
                        .putLong("lastCheck", System.currentTimeMillis())
                        .putString("plan", plan)
                        .putString("checksum", computeChecksum(key, expiresAt))
                        .apply()

                    val status = LicenseStatus(
                        state = if (daysLeft <= 0) LicenseState.EXPIRED else LicenseState.VALID,
                        message = if (daysLeft <= 0) "License expired" else "$daysLeft days remaining",
                        expiresAt = expiresAt,
                        daysLeft = daysLeft,
                        plan = plan
                    )
                    _status.value = status
                    status
                } else {
                    val error = result.optString("error", "License invalid")
                    val state = when {
                        error.contains("revoked", true) -> LicenseState.REVOKED
                        error.contains("expired", true) -> LicenseState.EXPIRED
                        else -> LicenseState.INVALID
                    }
                    clearLicense()
                    val status = LicenseStatus(state = state, message = error)
                    _status.value = status
                    status
                }
            } catch (e: Exception) {
                // Offline: use grace period
                Log.w(TAG, "Verification failed (offline): ${e.message}")
                val lastCheck = prefs.getLong("lastCheck", 0)
                val elapsed = System.currentTimeMillis() - lastCheck

                if (elapsed < OFFLINE_GRACE_MS && storedExpires > System.currentTimeMillis()) {
                    val daysLeft = ((storedExpires - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    val status = LicenseStatus(
                        state = LicenseState.OFFLINE_GRACE,
                        message = "Offline mode ($daysLeft days left)",
                        expiresAt = storedExpires,
                        daysLeft = daysLeft,
                        plan = prefs.getString("plan", "") ?: ""
                    )
                    _status.value = status
                    status
                } else {
                    clearLicense()
                    val status = LicenseStatus(
                        state = LicenseState.ERROR,
                        message = "Cannot verify license. Connect to internet."
                    )
                    _status.value = status
                    status
                }
            }
        }
    }

    fun clearLicense() {
        prefs.edit().clear().apply()
        _status.value = LicenseStatus(state = LicenseState.NO_KEY)
    }

    fun isValid(): Boolean {
        val state = _status.value.state
        return state == LicenseState.VALID || state == LicenseState.OFFLINE_GRACE
    }

    // --- API ---
    private fun apiCall(endpoint: String, params: Map<String, String>): JSONObject {
        val url = URL("$LICENSE_API/$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true

        val body = JSONObject(params).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }

        val response = if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: """{"error":"HTTP ${conn.responseCode}"}"""
        }
        conn.disconnect()
        return JSONObject(response)
    }

    // --- Crypto ---
    private val CRYPTO_KEY = sha256("ClawLauncher2026${context.packageName}").take(16)

    private fun encrypt(text: String): String {
        if (text.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CRYPTO_KEY.toByteArray(), "AES"))
        return android.util.Base64.encodeToString(cipher.doFinal(text.toByteArray()), android.util.Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        if (text.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(CRYPTO_KEY.toByteArray(), "AES"))
            String(cipher.doFinal(android.util.Base64.decode(text, android.util.Base64.NO_WRAP)))
        } catch (_: Exception) { "" }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeChecksum(key: String, expires: Long): String {
        return sha256("$key|$expires|${getDeviceId()}|ClawIntegrity2026")
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
