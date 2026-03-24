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
    val aiProvider: String = "openrouter", // openrouter, google, openai, anthropic
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
    private val envFile: File get() = File(baseDir, ".env")

    init {
        baseDir.mkdirs()
        workspaceDir.mkdirs()
        loadConfig()
    }

    private fun loadConfig() {
        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                _config.value = ClawConfig(
                    telegramBotToken = json.optJSONObject("channels")
                        ?.optJSONObject("telegram")
                        ?.optString("token", "") ?: "",
                    telegramAllowedUsers = json.optJSONObject("channels")
                        ?.optJSONObject("telegram")
                        ?.optJSONArray("allowedUsers")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList(),
                    aiProvider = json.optString("provider", "openrouter"),
                    aiApiKey = json.optString("apiKey", ""),
                    aiModel = json.optString("model", "anthropic/claude-sonnet-4"),
                    port = json.optInt("port", 3000),
                    discordBotToken = json.optJSONObject("channels")
                        ?.optJSONObject("discord")
                        ?.optString("token", "") ?: "",
                )
            } catch (_: Exception) { }
        }
    }

    fun updateConfig(newConfig: ClawConfig) {
        _config.value = newConfig
    }

    suspend fun saveConfig() {
        withContext(Dispatchers.IO) {
            val c = _config.value
            val json = JSONObject()

            // Model & AI provider
            json.put("model", c.aiModel)

            // Channels
            val channels = JSONObject()

            // Telegram
            if (c.telegramBotToken.isNotBlank()) {
                val telegram = JSONObject()
                telegram.put("token", c.telegramBotToken)
                if (c.telegramAllowedUsers.isNotEmpty()) {
                    telegram.put("allowedUsers", JSONArray(c.telegramAllowedUsers))
                }
                channels.put("telegram", telegram)
            }

            // Discord
            if (c.discordBotToken.isNotBlank()) {
                val discord = JSONObject()
                discord.put("token", c.discordBotToken)
                channels.put("discord", discord)
            }

            if (channels.length() > 0) {
                json.put("channels", channels)
            }

            json.put("port", c.port)

            configFile.writeText(json.toString(2))

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
            }
            if (envLines.isNotEmpty()) {
                envFile.writeText(envLines.joinToString("\n") + "\n")
            }

            // Create workspace files if they don't exist
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

    // Access via public properties: baseDir, workspaceDir, configFile

    fun getAvailableModels(): Map<String, List<Pair<String, String>>> {
        return mapOf(
            "openrouter" to listOf(
                "anthropic/claude-sonnet-4" to "Claude Sonnet 4 (Recommended)",
                "anthropic/claude-haiku-4" to "Claude Haiku 4 (Fast)",
                "anthropic/claude-opus-4" to "Claude Opus 4 (Best)",
                "openai/gpt-4o" to "GPT-4o",
                "openai/gpt-4o-mini" to "GPT-4o Mini (Cheap)",
                "google/gemini-2.0-flash-001" to "Gemini 2.0 Flash",
                "deepseek/deepseek-v3" to "DeepSeek V3",
                "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B",
            ),
            "google" to listOf(
                "gemini-2.0-flash" to "Gemini 2.0 Flash (Free)",
                "gemini-2.0-pro" to "Gemini 2.0 Pro",
                "gemini-1.5-pro" to "Gemini 1.5 Pro",
            ),
            "openai" to listOf(
                "gpt-4o" to "GPT-4o",
                "gpt-4o-mini" to "GPT-4o Mini",
                "gpt-4-turbo" to "GPT-4 Turbo",
            ),
            "anthropic" to listOf(
                "claude-sonnet-4-20250514" to "Claude Sonnet 4",
                "claude-haiku-4-20250514" to "Claude Haiku 4",
                "claude-opus-4-20250514" to "Claude Opus 4",
            )
        )
    }

    fun getProviderName(id: String): String = when (id) {
        "openrouter" -> "OpenRouter (Multi-provider)"
        "google" -> "Google Gemini"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        else -> id
    }

    fun getProviderKeyHint(id: String): String = when (id) {
        "openrouter" -> "sk-or-v1-..."
        "google" -> "AIzaSy..."
        "openai" -> "sk-..."
        "anthropic" -> "sk-ant-..."
        else -> "API Key"
    }
}
