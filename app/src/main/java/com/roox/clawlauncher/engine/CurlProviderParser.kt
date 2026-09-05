package com.roox.clawlauncher.engine

import org.json.JSONObject

/**
 * Parses a pasted `curl` command and derives a ready-to-use custom AI provider
 * for OpenClaw. Typical usage: user copies an example request from an AI
 * service docs page (Groq, DeepSeek, OpenRouter-compatible proxies, local
 * LM Studio, vLLM, ...) and pastes it here.
 *
 * Everything is best-effort: what can be detected is pre-filled, the user
 * reviews and confirms before the provider is saved.
 */
object CurlProviderParser {

    data class ParseResult(
        val ok: Boolean,
        val error: String? = null,
        val url: String = "",
        val baseUrl: String = "",
        val method: String = "POST",
        val apiKey: String = "",
        val apiKeyHeader: String = "",
        val model: String = "",
        val extraModels: List<String> = emptyList(),
        val api: String = OPENAI_API,
        val suggestedId: String = "",
        val suggestedName: String = "",
        val isLocal: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    const val OPENAI_API = "openai-completions"
    const val ANTHROPIC_API = "anthropic-messages"
    const val GOOGLE_API = "google-generative-ai"

    fun parse(raw: String): ParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return err("Paste a curl command first.")
        if (!trimmed.startsWith("curl", ignoreCase = true)) {
            return err("The text doesn't start with \"curl\". Paste the full curl command.")
        }

        // Normalize line continuations: "curl ... \↵  -H ..." → one space-joined line
        val normalized = trimmed
            .replace(Regex("\\\\\\s*\\n\\s*"), " ")
            .replace(Regex("\\s+"), " ")

        val tokens = tokenize(normalized)
        if (tokens.size < 2) return err("Incomplete curl command.")

        var method = "GET"
        var url = ""
        val headers = linkedMapOf<String, String>()
        var body = ""
        var i = 1 // skip "curl"
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-X" || t == "--request" ->
                    if (i + 1 < tokens.size) { method = tokens[++i].uppercase() }
                t == "-H" || t == "--header" ->
                    if (i + 1 < tokens.size) {
                        val h = tokens[++i]
                        val k = h.substringBefore(':').trim()
                        val v = h.substringAfter(':', "").trim()
                        if (k.isNotEmpty()) headers[k.lowercase()] = v
                    }
                t == "-d" || t == "--data" || t == "--data-raw" || t == "--data-binary" || t == "--data-ascii" || t == "--json" ->
                    if (i + 1 < tokens.size) { body = tokens[++i]; if (method == "GET") method = "POST" }
                t.startsWith("--header=") ->
                    tokens[i].removePrefix("--header=").trim().let { h ->
                        headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':', "").trim()
                    }
                t.startsWith("--data=") || t.startsWith("--data-raw=") || t.startsWith("--json=") ->
                    tokens[i].substringAfter('=').let { body = it; if (method == "GET") method = "POST" }
                t.startsWith("-X") && t.length > 2 -> method = t.removePrefix("-X").uppercase()
                t.startsWith("-H") && t.length > 2 -> tokens[i].removePrefix("-H").trim().let { h ->
                    headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':', "").trim()
                }
                t == "-u" || t == "--user" -> {
                    if (i + 1 < tokens.size) {
                        // basic auth "user:key" → treat key as api key if user looks like a placeholder
                        val uv = tokens[++i]
                        headers["authorization"] = "Basic " + android.util.Base64.encodeToString(
                            uv.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                }
                t.startsWith("-") -> { /* other flags ignored; may consume a value below */ }
                url.isEmpty() && (t.startsWith("http://") || t.startsWith("https://")) -> {
                    url = t.removeSurrounding("\"").removeSurrounding("'").trim()
                }
            }
            i++
        }

        if (url.isEmpty()) return err("No URL found in the curl command.")
        if (!url.startsWith("http")) return err("URL must start with http:// or https://.")

        val warnings = mutableListOf<String>()

        // ── API key ─────────────────────────────────────────────────────────
        var apiKey = ""
        var apiKeyHeader = ""
        headers["authorization"]?.let { v ->
            when {
                v.startsWith("Bearer ", ignoreCase = true) -> {
                    apiKey = v.substringAfter(" ").trim()
                    apiKeyHeader = "Authorization: Bearer"
                }
                v.startsWith("Basic ") -> {
                    apiKey = "" // basic auth handled separately; not an AI API key
                }
            }
        }
        if (apiKey.isEmpty()) {
            val keyHeaderNames = listOf("x-api-key", "api-key", "x-goog-api-key", "x-apikey", "ocp-apim-subscription-key")
            val foundKey = keyHeaderNames.firstOrNull { headers[it] != null }
            if (foundKey != null) {
                apiKey = headers[foundKey].orEmpty().trim()
                apiKeyHeader = foundKey.split('-')
                    .joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) } + ":"
            }
        }
        // Key in URL query (?key=AIza...)
        if (apiKey.isEmpty() && url.contains("?")) {
            val query = url.substringAfter('?')
            for (p in query.split('&')) {
                if (p.substringBefore('=').equals("key", ignoreCase = true)) {
                    apiKey = p.substringAfter('=').trim()
                    apiKeyHeader = "url ?key="
                }
            }
        }
        if (apiKey.matches(Regex("\\$\\{?\\w+(API_?KEY|TOKEN)\\}?|\\{\\{\\w+\\}\\}|<.*>", RegexOption.IGNORE_CASE))) {
            warnings += "API key looks like a placeholder — fill in your real key."
            apiKey = ""
        }

        // ── Model from request body ─────────────────────────────────────────
        var model = ""
        val extraModels = mutableListOf<String>()
        if (body.isNotEmpty()) {
            val clean = body.removeSurrounding("\"").removeSurrounding("'")
            try {
                val j = JSONObject(clean)
                model = j.optString("model")
                j.optJSONArray("models")?.let { arr ->
                    for (n in 0 until arr.length()) extraModels += arr.optString(n)
                }
            } catch (_: Exception) {
                // body may use shell $'' escapes or be partial — try regex fallback
                Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(clean)?.let { model = it.groupValues[1] }
            }
        }

        // ── API format + base URL ───────────────────────────────────────────
        val lower = url.lowercase()
        val api: String
        var baseUrl = url
        when {
            "anthropic.com" in lower || lower.contains("/v1/messages") || headers.containsKey("anthropic-version") -> {
                api = ANTHROPIC_API
                baseUrl = url.replace(Regex("/(v1/)?messages(\\?.*)?$", RegexOption.IGNORE_CASE), "")
            }
            "generativelanguage.googleapis.com" in lower || ":generatecontent" in lower -> {
                api = GOOGLE_API
                baseUrl = url.substringBefore("/models/").substringBefore("/v1")
                if (url.contains("?key=")) baseUrl = baseUrl.substringBefore('?')
                warnings += "Google Generative Language API detected — consider the built-in \"Google Gemini (API Key)\" provider instead."
            }
            lower.contains("/chat/completions") -> {
                api = OPENAI_API
                baseUrl = url.replace(Regex("/chat/completions(\\?.*)?$", RegexOption.IGNORE_CASE), "")
            }
            lower.contains("/completions") -> {
                api = OPENAI_API
                baseUrl = url.replace(Regex("/completions(\\?.*)?$", RegexOption.IGNORE_CASE), "")
            }
            lower.endsWith("/v1") || lower.matches(Regex(".*://[^/]+/v\\d+/?")) -> {
                api = OPENAI_API
                baseUrl = url
                warnings += "Endpoint has no recognisable path — assumed OpenAI-compatible."
            }
            else -> {
                api = OPENAI_API
                baseUrl = url
                warnings += "Couldn't detect the API format — assumed OpenAI-compatible (openai-completions)."
            }
        }
        baseUrl = baseUrl.trimEnd('/')

        val host = Regex("https?://([^/:?]+)").find(url)?.groupValues?.get(1) ?: ""
        val isLocal = host.startsWith("127.") || host.startsWith("localhost") ||
            host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local")

        // ── Suggested id / name ─────────────────────────────────────────────
        val hostCore = host
            .removePrefix("www.").removePrefix("api.")
            .substringBefore('.')
            .lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        val id = if (hostCore.isBlank()) "custom-provider" else hostCore
        val name = if (hostCore.isBlank()) "Custom Provider"
            else hostCore.split('-', '_').joinToString(" ") { w ->
                w.replaceFirstChar { it.uppercaseChar() }
            }

        if (model.isBlank()) warnings += "No model found in the request body — add a model manually."

        return ParseResult(
            ok = true,
            url = url,
            baseUrl = baseUrl,
            method = method,
            apiKey = apiKey,
            apiKeyHeader = apiKeyHeader,
            model = model.removePrefix("models/"),
            extraModels = extraModels,
            api = api,
            suggestedId = id,
            suggestedName = name,
            isLocal = isLocal,
            warnings = warnings
        )
    }

    private fun err(msg: String) = ParseResult(ok = false, error = msg)

    /** Simple shell tokenizer supporting single/double quotes. */
    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasContent = false
        for (c in input) {
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; hasContent = true }
                c == '"' && !inSingle -> { inDouble = !inDouble; hasContent = true }
                c == '\\' && !inSingle && !inDouble -> { /* drop escapes only between words */ }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (cur.isNotEmpty() || hasContent) { out += cur.toString(); cur.clear(); hasContent = false }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || hasContent) out += cur.toString()
        return out
    }
}
