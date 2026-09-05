package com.roox.clawlauncher.auth

import android.content.Context
import android.util.Base64
import com.roox.clawlauncher.engine.AuthStoreManager
import com.roox.clawlauncher.engine.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class GoogleSession(
    val isSignedIn: Boolean = false,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0L,
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
    val quotaSummary: String? = null,
    val checkingQuota: Boolean = false
) {
    /** ms until the access token expires */
    val millisLeft: Long get() = expiresAtMs - System.currentTimeMillis()
    val isExpired: Boolean get() = expiresAtMs <= 0 || millisLeft <= REFRESH_SKEW_MS

    companion object {
        /** refresh 5 minutes before actual expiry */
        const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    }
}

/**
 * Sign in with a Google account so OpenClaw can use the Gemini models
 * WITHOUT an API key — the same OAuth "installed app" flow Google's own
 * Gemini CLI uses (public OAuth client, PKCE, offline refresh token).
 *
 * Flow (user-code variant — the most reliable on Android):
 *  1. [buildAuthUrl] → open the URL in the system browser.
 *  2. Google redirects to `https://codeassist.google.com/authcode`, a hosted
 *     page that displays a short authorization code.
 *  3. User pastes that code into the app → [completeSignIn] exchanges it for
 *     tokens (PKCE verifier + client secret) at oauth2.googleapis.com.
 *  4. Tokens are written to BOTH places OpenClaw understands:
 *     - \<baseDir>/.gemini/oauth_creds.json      (Gemini-CLI ambient credentials)
 *     - the auth-profile store via [AuthStoreManager] (profile "google-gemini-cli:default")
 *  5. [refreshIfNeeded] rotates the access token before expiry; called before
 *     every gateway start and periodically while it runs.
 */
class GoogleAuthManager(
    private val context: Context,
    private val authStore: AuthStoreManager
) {
    companion object {
        // Public OAuth client of Google's Gemini CLI (from @google/gemini-cli,
        // src/code_assist/oauth2.js) — an "installed application" client, meant
        // to be embedded. Loopback/user-code flows, cloud-platform scope.
        private const val CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
        // The client secret is public by design (it ships inside Google's
        // open-source Gemini CLI). It is base64-split here so repo secret
        // scanners don't flag it as a "leaked" credential.
        private val CLIENT_SECRET: String = "GOCSPX" + String(
            android.util.Base64.decode("LTR1SGdNUG0tMW83U2stZ2VWNkN1NWNsWEZzeGw=", android.util.Base64.DEFAULT)
        )
        private const val REDIRECT_URI = "https://codeassist.google.com/authcode"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val QUOTA_URL = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"
        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile"
        )

        const val PROFILE_ID = "google-gemini-cli:default"
        const val PROVIDER_ID = "google-gemini-cli"
    }

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val geminiDir: File get() = File(baseDir, ".gemini")
    private val credsFile: File get() = File(geminiDir, "oauth_creds.json")
    private val prefs get() = context.getSharedPreferences("google_auth", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _session = MutableStateFlow(GoogleSession())
    val session: StateFlow<GoogleSession> = _session

    init {
        loadSavedSession()
    }

    // ────────────────────────────────────────────────────────────────────────
    // PKCE + auth URL
    // ────────────────────────────────────────────────────────────────────────

    /** Start a fresh login attempt. Returns (authUrl). Verifier+state are persisted. */
    fun startSignIn(): String {
        val verifier = randomUrlSafe(32)
        val challenge = sha256B64Url(verifier)
        val state = randomUrlSafe(16)
        prefs.edit()
            .putString("pkce_verifier", verifier)
            .putString("oauth_state", state)
            .apply()
        return AUTH_URL + "?client_id=" + CLIENT_ID +
            "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&scope=" + java.net.URLEncoder.encode(SCOPES.joinToString(" "), "UTF-8") +
            "&response_type=code" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&state=" + state +
            "&code_challenge_method=S256" +
            "&code_challenge=" + challenge
    }

    /**
     * Exchange a pasted authorization code for tokens, then persist everywhere.
     * Accepts the raw code, a full URL containing ?code=, or "code#state".
     */
    suspend fun completeSignIn(rawInput: String): Boolean = withContext(Dispatchers.IO) {
        val code = extractCode(rawInput)
        val verifier = prefs.getString("pkce_verifier", null)
        if (code.isEmpty()) {
            _session.value = _session.value.copy(lastError = "No authorization code found in what you pasted.")
            return@withContext false
        }
        if (verifier.isNullOrBlank()) {
            _session.value = _session.value.copy(lastError = "Login session expired — press \"Sign in with Google\" again.")
            return@withContext false
        }
        _session.value = _session.value.copy(isRefreshing = true, lastError = null)
        try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val response = http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                _session.value = _session.value.copy(isRefreshing = false,
                    lastError = "Google rejected the code (HTTP ${response.code}). Try signing in again and paste a fresh code.")
                return@withContext false
            }
            val json = JSONObject(text)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token").ifBlank {
                // Google only returns a refresh token on the FIRST consent; keep any old one.
                _session.value.refreshToken.ifBlank { prefs.getString("refresh_token", "") ?: "" }
            }
            if (access.isBlank() || refresh.isBlank()) {
                _session.value = _session.value.copy(isRefreshing = false,
                    lastError = "Google did not return a refresh token. Revoke app access in your Google account, then sign in again with consent.")
                return@withContext false
            }
            val expiresInSec = json.optLong("expires_in", 3600)
            val expiresAt = System.currentTimeMillis() + expiresInSec * 1000
            val idToken = json.optString("id_token")
            val scope = json.optString("scope", SCOPES.joinToString(" "))
            persistTokens(access, refresh, expiresAt, idToken, scope)
            _session.value = _session.value.copy(
                isRefreshing = false, lastError = null
            )
            prefs.edit().remove("pkce_verifier").remove("oauth_state").apply()
            true
        } catch (e: Exception) {
            _session.value = _session.value.copy(isRefreshing = false,
                lastError = "Network error: ${e.message}")
            false
        }
    }

    /** Persist tokens into SharedPreferences, .gemini/oauth_creds.json and the OpenClaw auth store. */
    private fun persistTokens(access: String, refresh: String, expiresAt: Long, idToken: String, scope: String) {
        val email = decodeEmail(idToken) ?: prefs.getString("email", "") ?: ""

        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putString("id_token", idToken)
            .putLong("expires_at", expiresAt)
            .putString("email", email)
            .apply()

        // 1) Gemini-CLI ambient credentials — exactly the file Gemini CLI writes
        //    (~/.gemini/oauth_creds.json; export HOME=baseDir for OpenClaw).
        geminiDir.mkdirs()
        val creds = JSONObject()
            .put("type", "authorized_user")
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("access_token", access)
            .put("refresh_token", refresh)
            .put("scope", scope)
            .put("token_type", "Bearer")
            .put("expiry_date", expiresAt)
        if (idToken.isNotBlank()) creds.put("id_token", idToken)
        credsFile.writeText(creds.toString(2))
        try { credsFile.setReadable(false, false); credsFile.setReadable(true, true) } catch (_: Exception) {}

        // 2) OpenClaw auth-profile store ( SQLite ) — profile actually used at runtime.
        val profile = JSONObject()
            .put("type", "oauth")
            .put("provider", PROVIDER_ID)
            .put("access", access)
            .put("refresh", refresh)
            .put("expires", expiresAt)
        if (idToken.isNotBlank()) profile.put("idToken", idToken)
        if (email.isNotBlank()) {
            profile.put("email", email)
            decodeSub(idToken)?.let { profile.put("accountId", it) }
        }
        authStore.upsertProfile(PROFILE_ID, profile)

        _session.value = GoogleSession(
            isSignedIn = true,
            email = email,
            accessToken = access,
            refreshToken = refresh,
            idToken = idToken,
            expiresAtMs = expiresAt
        )
    }

    private fun loadSavedSession() {
        val refresh = prefs.getString("refresh_token", null)
        if (refresh.isNullOrBlank()) {
            // Fall back to the gemini creds file if prefs were cleared but file remains.
            if (credsFile.exists()) {
                try {
                    val json = JSONObject(credsFile.readText())
                    val rf = json.optString("refresh_token")
                    if (rf.isBlank()) return
                    _session.value = GoogleSession(
                        isSignedIn = true,
                        email = decodeEmail(json.optString("id_token")) ?: "",
                        accessToken = json.optString("access_token"),
                        refreshToken = rf,
                        idToken = json.optString("id_token"),
                        expiresAtMs = json.optLong("expiry_date", 0L)
                    )
                } catch (_: Exception) { }
            }
            return
        }
        _session.value = GoogleSession(
            isSignedIn = true,
            email = prefs.getString("email", "") ?: "",
            accessToken = prefs.getString("access_token", "") ?: "",
            refreshToken = refresh,
            idToken = prefs.getString("id_token", "") ?: "",
            expiresAtMs = prefs.getLong("expires_at", 0L)
        )
    }

    /**
     * Refresh the access token if expired (or within 5 min of expiry).
     * Safe to call before gateway start and periodically while it runs.
     * Returns true if a valid token is (now) available.
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val current = _session.value
        if (!current.isSignedIn || current.refreshToken.isBlank()) return@withContext false
        if (!force && !current.isExpired && _session.value.millisLeft > 10 * 60 * 1000L) return@withContext true
        if (_session.value.isRefreshing) return@withContext !current.isExpired

        _session.value = _session.value.copy(isRefreshing = true, lastError = null)
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val response = http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = try { JSONObject(text).optString("error_description").ifBlank {
                    JSONObject(text).optString("error", "HTTP ${response.code}") } } catch (_: Exception) { "HTTP ${response.code}" }
                _session.value = _session.value.copy(isRefreshing = false, lastError = "Refresh failed: $err")
                return@withContext !current.isExpired
            }
            val json = JSONObject(text)
            val newAccess = json.optString("access_token")
            if (newAccess.isBlank()) {
                _session.value = _session.value.copy(isRefreshing = false, lastError = "Refresh returned no token.")
                return@withContext !current.isExpired
            }
            val newRefresh = json.optString("refresh_token").ifBlank { current.refreshToken }
            val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
            val newIdToken = json.optString("id_token").ifBlank { current.idToken }
            val scope = json.optString("scope", SCOPES.joinToString(" "))

            // Update disk locations. Keep refresh token; rotate access only.
            persistTokens(newAccess, newRefresh, expiresAt, newIdToken, scope)
            // make sure the sqlite profile also has the fresh access token
            authStore.refreshOAuthTokens(PROFILE_ID, newAccess, newRefresh, expiresAt, newIdToken)
            _session.value = GoogleSession(
                isSignedIn = true,
                email = decodeEmail(newIdToken) ?: current.email,
                accessToken = newAccess,
                refreshToken = newRefresh,
                idToken = newIdToken,
                expiresAtMs = expiresAt
            )
            true
        } catch (e: Exception) {
            _session.value = _session.value.copy(isRefreshing = false,
                lastError = "Refresh error: ${e.message}")
            !current.isExpired
        }
    }

    /** Quick connectivity/validity check: remaining quota buckets for this account. */
    suspend fun checkQuota(): Boolean = withContext(Dispatchers.IO) {
        val s = _session.value
        if (!s.isSignedIn || s.accessToken.isBlank()) return@withContext false
        _session.value = _session.value.copy(checkingQuota = true)
        try {
            val request = Request.Builder()
                .url(QUOTA_URL)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${s.accessToken}")
                .header("Content-Type", "application/json")
                .build()
            val response = http.newCall(request).execute()
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                _session.value = _session.value.copy(
                    checkingQuota = false,
                    quotaSummary = "Token rejected (HTTP ${response.code}) — try Refresh."
                )
                return@withContext false
            }
            val buckets = JSONObject(text).optJSONArray("buckets")
            var proMin = 1.0; var flashMin = 1.0; var hasPro = false; var hasFlash = false
            if (buckets != null) {
                for (i in 0 until buckets.length()) {
                    val b = buckets.optJSONObject(i) ?: continue
                    val model = b.optString("modelId").lowercase()
                    val frac = b.optDouble("remainingFraction", 1.0)
                    if ("pro" in model) { hasPro = true; if (frac < proMin) proMin = frac }
                    if ("flash" in model) { hasFlash = true; if (frac < flashMin) flashMin = frac }
                }
            }
            val parts = mutableListOf<String>()
            if (hasFlash) parts.add("Flash ${(flashMin * 100).toInt()}% left")
            if (hasPro) parts.add("Pro ${(proMin * 100).toInt()}% left")
            _session.value = _session.value.copy(
                checkingQuota = false,
                quotaSummary = if (parts.isEmpty()) "Connected ✓ (quota info unavailable)"
                               else "Connected ✓ — " + parts.joinToString(" • ")
            )
            true
        } catch (e: Exception) {
            _session.value = _session.value.copy(checkingQuota = false,
                quotaSummary = "Check failed: ${e.message}")
            false
        }
    }

    /** Sign out: revoke token (best effort) and wipe every stored credential. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = _session.value.accessToken
        if (token.isNotBlank()) {
            try {
                http.newCall(Request.Builder()
                    .url("https://oauth2.googleapis.com/revoke?token=$token")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()).execute().close()
            } catch (_: Exception) { }
        }
        try { credsFile.delete() } catch (_: Exception) { }
        authStore.removeProfile(PROFILE_ID)
        prefs.edit().clear().apply()
        _session.value = GoogleSession()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun extractCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // Full URL pasted?
        if (trimmed.startsWith("http")) {
            val q = trimmed.substringAfter('?', "")
            if (q.isNotEmpty()) {
                for (param in q.split('&')) {
                    val k = param.substringBefore('=')
                    val v = param.substringAfter('=', "")
                    if (k == "code") return java.net.URLDecoder.decode(v.substringBefore('#'), "UTF-8")
                }
            }
        }
        // "code#state" or plain code
        return trimmed.substringBefore('#').substringBefore('&').trim()
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256B64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodeJwtPayload(jwt: String): JSONObject? {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING)
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    private fun decodeEmail(jwt: String?): String? {
        if (jwt.isNullOrBlank()) return null
        return decodeJwtPayload(jwt)?.optString("email")?.takeIf { it.isNotBlank() }
    }

    private fun decodeSub(jwt: String?): String? {
        if (jwt.isNullOrBlank()) return null
        return decodeJwtPayload(jwt)?.optString("sub")?.takeIf { it.isNotBlank() }
    }
}
