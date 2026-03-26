package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClawConfig(
    // Telegram
    val telegramBotToken: String = "",
    val telegramAllowedUsers: List<String> = emptyList(),
    // AI Model
    val aiProvider: String = "openrouter", // openrouter, google, openai, anthropic, gemini-cli
    val aiApiKey: String = "",
    val aiModel: String = "anthropic/claude-sonnet-4",
    // Server
    val port: Int = 3000,
    // WhatsApp
    val whatsappEnabled: Boolean = false,
    // Discord
    val discordBotToken: String = "",
    // Advanced
    val openclawVersion: String = "latest"
)

class ConfigManager(private val context: Context) {
    private val _config = MutableStateFlow(ClawConfig())
    val config: StateFlow<ClawConfig> = _config

    val baseDir: File get() = File(context.filesDir, "openclaw")
    val workspaceDir: File get() = File(baseDir, "workspace")
    val configFile: File get() = File(baseDir, "openclaw.json")
    // Secondary config location inside .openclaw subdirectory
    private val dotOpenclawDir: File get() = File(baseDir, ".openclaw")
    private val secondaryConfigFile: File get() = File(dotOpenclawDir, "openclaw.json")
    private val envFile: File get() = File(baseDir, ".env")

    init {
        baseDir.mkdirs()
        workspaceDir.mkdirs()
        dotOpenclawDir.mkdirs()
        loadConfig()
    }

    private fun loadConfig() {
        // Try secondary location first (.openclaw/), then primary
        val file = if (secondaryConfigFile.exists()) secondaryConfigFile
                   else if (configFile.exists()) configFile
                   else return
        try {
            val json = JSONObject(file.readText())

            // Read Telegram: channels.telegram.botToken (correct schema)
            val telegram = json.optJSONObject("channels")?.optJSONObject("telegram")
            val botToken = telegram?.optString("botToken", "")
                ?: telegram?.optString("token", "") // fallback old format
                ?: ""
            val allowedUsers = telegram?.optJSONArray("allowedUsers")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            // Read model: agents.defaults.model.primary
            val model = json.optJSONObject("agents")
                ?.optJSONObject("defaults")
                ?.optJSONObject("model")
                ?.optString("primary", "")
                ?: json.optString("model", "anthropic/claude-sonnet-4")

            // Read port: gateway.port
            val port = json.optJSONObject("gateway")?.optInt("port", 3000)
                ?: json.optInt("port", 3000)

            // Read Discord: channels.discord.botToken
            val discordToken = json.optJSONObject("channels")?.optJSONObject("discord")
                ?.optString("botToken", "")
                ?: json.optJSONObject("channels")?.optJSONObject("discord")
                    ?.optString("token", "")
                ?: ""

            // Read API key from .env file
            var apiKey = ""
            var provider = "openrouter"
            val envContent = if (envFile.exists()) envFile.readText() else
                if (File(dotOpenclawDir, ".env").exists()) File(dotOpenclawDir, ".env").readText() else ""
            for (line in envContent.lines()) {
                when {
                    line.startsWith("OPENROUTER_API_KEY=") -> {
                        apiKey = line.substringAfter("="); provider = "openrouter"
                    }
                    line.startsWith("GEMINI_API_KEY=") -> {
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

            _config.value = ClawConfig(
                telegramBotToken = botToken,
                telegramAllowedUsers = allowedUsers,
                aiProvider = provider,
                aiApiKey = apiKey,
                aiModel = model,
                port = port,
                discordBotToken = discordToken
            )
        } catch (_: Exception) { }
    }

    fun updateConfig(newConfig: ClawConfig) {
        _config.value = newConfig
    }

    // Returns true if the given provider requires an API key
    fun providerRequiresApiKey(provider: String): Boolean {
        return provider != "gemini-cli"
    }

    suspend fun saveConfig() {
        withContext(Dispatchers.IO) {
            val c = _config.value
            val json = JSONObject()

            // Gateway settings (correct schema)
            val gateway = JSONObject()
            gateway.put("mode", "local")
            gateway.put("bind", "loopback")
            gateway.put("port", c.port)
            json.put("gateway", gateway)

            // Agents → defaults → model → primary (correct schema)
            val modelObj = JSONObject()
            modelObj.put("primary", c.aiModel)
            val defaults = JSONObject()
            defaults.put("model", modelObj)
            defaults.put("workspace", baseDir.absolutePath + "/workspace")
            val agents = JSONObject()
            agents.put("defaults", defaults)
            json.put("agents", agents)

            // Channels (correct schema: botToken not token)
            val channels = JSONObject()

            if (c.telegramBotToken.isNotBlank()) {
                val telegram = JSONObject()
                telegram.put("botToken", c.telegramBotToken)
                telegram.put("enabled", true)
                telegram.put("dmPolicy", "pairing")
                channels.put("telegram", telegram)
            }

            if (c.discordBotToken.isNotBlank()) {
                val discord = JSONObject()
                discord.put("botToken", c.discordBotToken)
                discord.put("enabled", true)
                channels.put("discord", discord)
            }

            if (channels.length() > 0) {
                json.put("channels", channels)
            }

            val configText = json.toString(2)

            // Write to BOTH config locations
            configFile.writeText(configText)
            dotOpenclawDir.mkdirs()
            secondaryConfigFile.writeText(configText)

            // Write .env with API key for the provider
            val envLines = mutableListOf<String>()
            when (c.aiProvider) {
                "openrouter" -> {
                    if (c.aiApiKey.isNotBlank()) envLines.add("OPENROUTER_API_KEY=${c.aiApiKey}")
                }
                "google" -> {
                    if (c.aiApiKey.isNotBlank()) envLines.add("GEMINI_API_KEY=${c.aiApiKey}")
                }
                "openai" -> {
                    if (c.aiApiKey.isNotBlank()) envLines.add("OPENAI_API_KEY=${c.aiApiKey}")
                }
                "anthropic" -> {
                    if (c.aiApiKey.isNotBlank()) envLines.add("ANTHROPIC_API_KEY=${c.aiApiKey}")
                }
                "gemini-cli" -> { }
            }

            // Write .env to BOTH locations
            val envContent = envLines.joinToString("\n") + "\n"
            if (envLines.isNotEmpty()) {
                envFile.writeText(envContent)
                File(dotOpenclawDir, ".env").writeText(envContent)
            }

            ensureWorkspaceFiles()
        }
    }

    private fun ensureWorkspaceFiles() {
        val agentsMd = File(workspaceDir, "AGENTS.md")
        if (!agentsMd.exists()) {
            agentsMd.writeText("# AGENTS.md\n\nYour workspace.\n")
        }
        val soulMd = File(workspaceDir, "SOUL.md")
        if (!soulMd.exists()) {
            soulMd.writeText("# SOUL.md\n\nWho you are.\n")
        }
    }

    fun getAvailableModels(): Map<String, List<Pair<String, String>>> {
        return mapOf(
            "openrouter" to listOf(
                // Free models first
                "google/gemini-2.0-flash-exp" to "Gemini 2.0 Flash Exp (Free)",
                "google/gemini-2.5-pro-exp-03-25" to "Gemini 2.5 Pro Exp (Free)",
                "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B (Free Tier)",
                // Recommended
                "anthropic/claude-sonnet-4" to "Claude Sonnet 4 (Recommended)",
                "anthropic/claude-haiku-4" to "Claude Haiku 4 (Fast)",
                "anthropic/claude-opus-4" to "Claude Opus 4 (Best)",
                // OpenAI
                "openai/gpt-4o" to "GPT-4o",
                "openai/gpt-4o-mini" to "GPT-4o Mini (Cheap)",
                "openai/o1-mini" to "o1-mini (Reasoning)",
                "openai/o3-mini" to "o3-mini (Reasoning)",
                // DeepSeek
                "deepseek/deepseek-r1" to "DeepSeek R1 (Reasoning)",
                "deepseek/deepseek-v3-0324" to "DeepSeek V3 (Fast)",
                // Others
                "mistralai/mistral-large-latest" to "Mistral Large",
                "qwen/qwen-2.5-72b-instruct" to "Qwen 2.5 72B",
                "x-ai/grok-2" to "Grok 2",
                "google/gemini-2.0-flash-001" to "Gemini 2.0 Flash",
            ),
            "google" to listOf(
                // Free models
                "gemini-2.5-pro-exp-03-25" to "Gemini 2.5 Pro Exp (Free!)",
                "gemini-2.0-flash" to "Gemini 2.0 Flash (Free!)",
                "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite (Free!)",
                // Paid
                "gemini-1.5-pro" to "Gemini 1.5 Pro",
                "gemini-1.5-flash" to "Gemini 1.5 Flash (Fast)",
            ),
            "openai" to listOf(
                "gpt-4o" to "GPT-4o (Best)",
                "gpt-4o-mini" to "GPT-4o Mini (Cheap)",
                "gpt-4-turbo" to "GPT-4 Turbo",
                "o1-mini" to "o1-mini (Reasoning)",
                "o3-mini" to "o3-mini (Reasoning)",
            ),
            "anthropic" to listOf(
                "claude-sonnet-4-20250514" to "Claude Sonnet 4 (Recommended)",
                "claude-haiku-4-20250514" to "Claude Haiku 4 (Fast)",
                "claude-opus-4-20250514" to "Claude Opus 4 (Best)",
            ),
            "gemini-cli" to listOf(
                "gemini-2.5-pro" to "Gemini 2.5 Pro (Default)",
                "gemini-2.0-flash" to "Gemini 2.0 Flash (Fast)",
                "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite (Lite)",
            )
        )
    }

    fun getProviderName(id: String): String = when (id) {
        "openrouter" -> "OpenRouter (Multi-provider)"
        "google" -> "Google Gemini (API Key)"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "gemini-cli" -> "Gemini (Google Account)"
        else -> id
    }

    fun getProviderKeyHint(id: String): String = when (id) {
        "openrouter" -> "sk-or-v1-..."
        "google" -> "AIzaSy..."
        "openai" -> "sk-..."
        "anthropic" -> "sk-ant-..."
        "gemini-cli" -> ""
        else -> "API Key"
    }
}
