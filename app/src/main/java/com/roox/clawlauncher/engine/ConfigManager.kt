package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CustomAiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val api: String = "openai-completions",
    val models: List<String> = emptyList()
)

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val api: String = "openai-completions",
    val models: List<String>
)

data class ClawConfig(
    // Telegram
    val telegramBotToken: String = "",
    val telegramAllowedUsers: List<String> = emptyList(),
    // AI Model
    val aiProvider: String = "openrouter", // built-in provider or custom provider id
    val aiApiKey: String = "",
    val aiModel: String = "anthropic/claude-sonnet-4",
    val customAiProviders: List<CustomAiProvider> = emptyList(),
    // Server
    val port: Int = 3000,
    // WhatsApp
    val whatsappEnabled: Boolean = false,
    // Discord
    val discordBotToken: String = "",
    // Advanced
    val openclawVersion: String = "latest",
    // Root access
    val rootEnabled: Boolean = false
)

class ConfigManager(private val context: Context) {
    private val _config = MutableStateFlow(ClawConfig())
    val config: StateFlow<ClawConfig> = _config

    val baseDir: File get() = File(context.filesDir, "openclaw")
    val workspaceDir: File get() = File(baseDir, "workspace")
    val configFile: File get() = File(baseDir, "openclaw.json")
    private val dotOpenclawDir: File get() = File(baseDir, ".openclaw")
    private val secondaryConfigFile: File get() = File(dotOpenclawDir, "openclaw.json")
    private val envFile: File get() = File(baseDir, ".env")

    private val builtInProviderIds = listOf("openrouter", "google", "openai", "anthropic", "gemini-cli")

    init {
        baseDir.mkdirs()
        workspaceDir.mkdirs()
        dotOpenclawDir.mkdirs()
        loadConfig()
    }

    private fun loadConfig() {
        val file = if (secondaryConfigFile.exists()) secondaryConfigFile
        else if (configFile.exists()) configFile
        else return

        try {
            val json = JSONObject(file.readText())

            val telegram = json.optJSONObject("channels")?.optJSONObject("telegram")
            val botToken = telegram?.optString("botToken", "")
                ?: telegram?.optString("token", "")
                ?: ""
            val allowedUsers = telegram?.optJSONArray("allowedUsers")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            val rawPrimary = json.optJSONObject("agents")
                ?.optJSONObject("defaults")
                ?.optJSONObject("model")
                ?.optString("primary", "")
                ?: json.optString("model", "")

            val discordToken = json.optJSONObject("channels")?.optJSONObject("discord")
                ?.optString("botToken", "")
                ?: json.optJSONObject("channels")?.optJSONObject("discord")
                    ?.optString("token", "")
                ?: ""

            val customProviders = parseCustomProviders(json.optJSONObject("models")?.optJSONObject("providers"))
            val providerAndModel = parseProviderAndModel(rawPrimary, customProviders)

            var apiKey = ""
            var provider = providerAndModel.first
            val envContent = if (envFile.exists()) envFile.readText()
            else if (File(dotOpenclawDir, ".env").exists()) File(dotOpenclawDir, ".env").readText()
            else ""
            for (line in envContent.lines()) {
                when {
                    line.startsWith("OPENROUTER_API_KEY=") -> {
                        apiKey = line.substringAfter("="); provider = "openrouter"
                    }
                    line.startsWith("GEMINI_API_KEY=") || line.startsWith("GOOGLE_API_KEY=") -> {
                        apiKey = line.substringAfter("="); provider = "google"
                    }
                    line.startsWith("OPENAI_API_KEY=") -> {
                        apiKey = line.substringAfter("="); provider = "openai"
                    }
                    line.startsWith("ANTHROPIC_API_KEY=") -> {
                        apiKey = line.substringAfter("="); provider = "anthropic"
                    }
                }
            }

            if (providerAndModel.first in customProviders.map { it.id }) {
                apiKey = customProviders.first { it.id == providerAndModel.first }.apiKey
            }

            val model = providerAndModel.second.ifBlank {
                if (provider == "openrouter") "anthropic/claude-sonnet-4" else ""
            }
            val port = json.optJSONObject("gateway")?.optInt("port", 3000)
                ?: json.optInt("port", 3000)

            _config.value = ClawConfig(
                telegramBotToken = botToken,
                telegramAllowedUsers = allowedUsers,
                aiProvider = provider,
                aiApiKey = apiKey,
                aiModel = model,
                customAiProviders = customProviders,
                port = port,
                discordBotToken = discordToken
            )
        } catch (_: Exception) {
            // Keep defaults when an older or partially-written config cannot be parsed.
        }
    }

    private fun parseProviderAndModel(
        rawPrimary: String,
        customProviders: List<CustomAiProvider>
    ): Pair<String, String> {
        if (rawPrimary.isBlank()) return "openrouter" to "anthropic/claude-sonnet-4"
        val providerIds = builtInProviderIds + customProviders.map { it.id }
        val provider = providerIds.firstOrNull { rawPrimary.startsWith("$it/") || rawPrimary == it }
            ?: "openrouter"
        val model = if (rawPrimary == provider) "" else rawPrimary.removePrefix("$provider/")
        return provider to model
    }

    private fun parseCustomProviders(providersJson: JSONObject?): List<CustomAiProvider> {
        if (providersJson == null) return emptyList()
        val result = mutableListOf<CustomAiProvider>()
        val keys = providersJson.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (id in builtInProviderIds) continue
            val provider = providersJson.optJSONObject(id) ?: continue
            val models = provider.optJSONArray("models")?.let { arr ->
                (0 until arr.length()).mapNotNull { index ->
                    val item = arr.opt(index)
                    when (item) {
                        is JSONObject -> item.optString("id").takeIf { it.isNotBlank() }
                        else -> item?.toString()?.takeIf { it.isNotBlank() }
                    }
                }
            } ?: emptyList()
            result += CustomAiProvider(
                id = id,
                name = provider.optString("name", id),
                baseUrl = provider.optString("baseUrl", ""),
                apiKey = provider.optString("apiKey", ""),
                api = provider.optString("api", "openai-completions"),
                models = models
            )
        }
        return result
    }

    fun updateConfig(newConfig: ClawConfig) {
        _config.value = newConfig
    }

    fun isCustomProvider(provider: String): Boolean = _config.value.customAiProviders.any { it.id == provider }

    fun getProviderIds(): List<String> = builtInProviderIds + _config.value.customAiProviders.map { it.id }

    fun getCustomProvider(provider: String): CustomAiProvider? =
        _config.value.customAiProviders.firstOrNull { it.id == provider }

    fun providerRequiresApiKey(provider: String): Boolean = provider != "gemini-cli"

    suspend fun saveConfig() {
        withContext(Dispatchers.IO) {
            val c = _config.value
            val json = JSONObject()

            val gateway = JSONObject()
            gateway.put("mode", "local")
            gateway.put("bind", "loopback")
            gateway.put("port", c.port)
            json.put("gateway", gateway)

            val modelObj = JSONObject()
            modelObj.put("primary", fullModelRef(c.aiProvider, c.aiModel))
            val defaults = JSONObject()
            defaults.put("model", modelObj)
            defaults.put("workspace", File(dotOpenclawDir, "workspace").absolutePath)
            val agents = JSONObject()
            agents.put("defaults", defaults)
            json.put("agents", agents)

            if (c.customAiProviders.isNotEmpty()) {
                val providerObjects = JSONObject()
                c.customAiProviders.forEach { custom ->
                    val providerJson = JSONObject()
                    providerJson.put("name", custom.name)
                    providerJson.put("baseUrl", custom.baseUrl.trim().trimEnd('/'))
                    providerJson.put("api", custom.api)
                    if (custom.apiKey.isNotBlank()) providerJson.put("apiKey", custom.apiKey)
                    val models = JSONArray()
                    custom.models.filter { it.isNotBlank() }.forEach { modelId ->
                        models.put(JSONObject().put("id", modelId.trim()).put("name", modelId.trim()))
                    }
                    providerJson.put("models", models)
                    providerObjects.put(custom.id, providerJson)
                }
                json.put("models", JSONObject().put("mode", "merge").put("providers", providerObjects))
            }

            if (c.aiApiKey.isNotBlank() && !isCustomProvider(c.aiProvider)) {
                val authObj = JSONObject()
                val profilesObj = JSONObject()
                val profileKey = "${c.aiProvider}:default"
                val profileVal = JSONObject()
                profileVal.put("mode", "api_key")
                profileVal.put("provider", c.aiProvider)
                profilesObj.put(profileKey, profileVal)
                authObj.put("profiles", profilesObj)
                json.put("auth", authObj)
            }

            val channels = JSONObject()
            if (c.telegramBotToken.isNotBlank()) {
                val telegram = JSONObject()
                telegram.put("botToken", c.telegramBotToken)
                telegram.put("enabled", true)
                telegram.put("dmPolicy", "open")
                telegram.put("allowFrom", JSONArray(listOf("*")))
                channels.put("telegram", telegram)
            }
            if (c.discordBotToken.isNotBlank()) {
                val discord = JSONObject()
                discord.put("botToken", c.discordBotToken)
                discord.put("enabled", true)
                channels.put("discord", discord)
            }
            if (channels.length() > 0) json.put("channels", channels)

            val configText = json.toString(2)
            configFile.writeText(configText)
            dotOpenclawDir.mkdirs()
            secondaryConfigFile.writeText(configText)

            val envLines = mutableListOf<String>()
            when (c.aiProvider) {
                "openrouter" -> if (c.aiApiKey.isNotBlank()) envLines.add("OPENROUTER_API_KEY=${c.aiApiKey}")
                "google" -> if (c.aiApiKey.isNotBlank()) envLines.add("GEMINI_API_KEY=${c.aiApiKey}")
                "openai" -> if (c.aiApiKey.isNotBlank()) envLines.add("OPENAI_API_KEY=${c.aiApiKey}")
                "anthropic" -> if (c.aiApiKey.isNotBlank()) envLines.add("ANTHROPIC_API_KEY=${c.aiApiKey}")
                "gemini-cli" -> Unit
            }
            if (envLines.isNotEmpty()) {
                val envContent = envLines.joinToString("\n") + "\n"
                envFile.writeText(envContent)
                File(dotOpenclawDir, ".env").writeText(envContent)
            }

            if (c.aiApiKey.isNotBlank() && !isCustomProvider(c.aiProvider)) {
                val provider = c.aiProvider
                val authProfiles = JSONObject().put("version", 1)
                val profile = JSONObject()
                    .put("key", c.aiApiKey)
                    .put("provider", provider)
                    .put("type", "api_key")
                authProfiles.put("profiles", JSONObject().put("$provider:default", profile))
                val authJson = authProfiles.toString(2)
                val paths = listOf(
                    File(dotOpenclawDir, "agents/main/agent"),
                    File(dotOpenclawDir, ".openclaw/agents/main/agent"),
                    File(baseDir, "agents/main/agent")
                )
                paths.forEach { path ->
                    path.mkdirs()
                    File(path, "auth-profiles.json").writeText(authJson)
                }
            }

            ensureWorkspaceFiles()
        }
    }

    private fun fullModelRef(provider: String, model: String): String {
        if (model.isBlank()) return model
        return if (model.startsWith("$provider/")) model else "$provider/$model"
    }

    private fun ensureWorkspaceFiles() {
        val agentsMd = File(workspaceDir, "AGENTS.md")
        if (!agentsMd.exists()) agentsMd.writeText("# AGENTS.md\n\nYour workspace.\n")
        val soulMd = File(workspaceDir, "SOUL.md")
        if (!soulMd.exists()) soulMd.writeText("# SOUL.md\n\nWho you are.\n")
    }

    fun getAvailableModels(): Map<String, List<Pair<String, String>>> {
        return mapOf(
            "openrouter" to listOf(
                "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
                "google/gemini-2.5-pro" to "Gemini 2.5 Pro",
                "anthropic/claude-sonnet-4" to "Claude Sonnet 4",
                "anthropic/claude-opus-4" to "Claude Opus 4",
                "openai/gpt-4o" to "GPT-4o",
                "openai/gpt-4o-mini" to "GPT-4o Mini",
                "deepseek/deepseek-chat" to "DeepSeek Chat",
                "deepseek/deepseek-r1" to "DeepSeek R1",
                "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B",
                "qwen/qwen-2.5-72b-instruct" to "Qwen 2.5 72B",
                "mistralai/mistral-large" to "Mistral Large",
                "x-ai/grok-3-mini-beta" to "Grok 3 Mini"
            ),
            "google" to listOf(
                "gemini-3.1-pro-preview" to "Gemini 3.1 Pro Preview",
                "gemini-3.5-flash" to "Gemini 3.5 Flash",
                "gemini-2.5-pro" to "Gemini 2.5 Pro",
                "gemini-2.5-flash" to "Gemini 2.5 Flash",
                "gemini-2.0-flash" to "Gemini 2.0 Flash"
            ),
            "openai" to listOf(
                "gpt-5.6-sol" to "GPT-5.6 Sol",
                "gpt-5.5" to "GPT-5.5",
                "gpt-4o" to "GPT-4o",
                "gpt-4o-mini" to "GPT-4o Mini",
                "o3-mini" to "o3-mini"
            ),
            "anthropic" to listOf(
                "claude-opus-5" to "Claude Opus 5",
                "claude-sonnet-4" to "Claude Sonnet 4",
                "claude-haiku-4" to "Claude Haiku 4"
            ),
            "gemini-cli" to listOf(
                "gemini-2.5-pro" to "Gemini 2.5 Pro",
                "gemini-2.0-flash" to "Gemini 2.0 Flash"
            )
        )
    }

    fun getPopularProviderPresets(): List<ProviderPreset> = listOf(
        ProviderPreset(
            "openrouter-custom", "OpenRouter (OpenAI-compatible)", "https://openrouter.ai/api/v1",
            models = listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet-4", "google/gemini-2.5-flash", "deepseek/deepseek-chat")
        ),
        ProviderPreset(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768")
        ),
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            models = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        ProviderPreset(
            "together", "Together AI", "https://api.together.xyz/v1",
            models = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo", "deepseek-ai/DeepSeek-R1")
        ),
        ProviderPreset(
            "fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1",
            models = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/deepseek-r1")
        ),
        ProviderPreset(
            "mistral-custom", "Mistral AI (OpenAI-compatible)", "https://api.mistral.ai/v1",
            models = listOf("mistral-large-latest", "mistral-small-latest", "codestral-latest")
        ),
        ProviderPreset(
            "xai", "xAI", "https://api.x.ai/v1",
            models = listOf("grok-3-latest", "grok-3-mini-latest")
        ),
        ProviderPreset(
            "perplexity", "Perplexity", "https://api.perplexity.ai",
            models = listOf("sonar", "sonar-pro", "sonar-reasoning-pro")
        ),
        ProviderPreset(
            "ollama", "Ollama (Local)", "http://127.0.0.1:11434/v1",
            models = listOf("llama3.3", "qwen2.5", "deepseek-r1")
        ),
        ProviderPreset(
            "lmstudio", "LM Studio (Local)", "http://127.0.0.1:1234/v1",
            models = listOf("local-model")
        )
    )

    fun getProviderName(id: String): String = when (id) {
        "openrouter" -> "OpenRouter (Multi-provider)"
        "google" -> "Google Gemini (API Key)"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "gemini-cli" -> "Gemini (Google Account)"
        else -> _config.value.customAiProviders.firstOrNull { it.id == id }?.name ?: id
    }

    fun getProviderKeyHint(id: String): String = when (id) {
        "openrouter" -> "sk-or-v1-..."
        "google" -> "AIzaSy..."
        "openai" -> "sk-..."
        "anthropic" -> "sk-ant-..."
        "gemini-cli" -> ""
        else -> "API key (optional for local providers)"
    }
}
