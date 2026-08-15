package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.engine.ClawConfig
import com.roox.clawlauncher.engine.ConfigManager
import com.roox.clawlauncher.engine.CustomAiProvider
import com.roox.clawlauncher.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    configManager: ConfigManager,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    val config by configManager.config.collectAsState()
    var localConfig by remember(config) { mutableStateOf(config) }
    var showApiKey by remember { mutableStateOf(false) }
    var showTelegramToken by remember { mutableStateOf(false) }
    var showDiscordToken by remember { mutableStateOf(false) }
    var showCustomApiKey by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    var telegramUserInput by remember(config.telegramAllowedUsers) {
        mutableStateOf(config.telegramAllowedUsers.joinToString(", "))
    }

    val builtInProviders = remember { listOf("openrouter", "google", "openai", "anthropic", "gemini-cli") }
    val providers = builtInProviders + localConfig.customAiProviders.map { it.id }
    val popularPresets = remember { configManager.getPopularProviderPresets() }

    var editingCustomId by remember { mutableStateOf<String?>(null) }
    var customId by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customApiKey by remember { mutableStateOf("") }
    var customApi by remember { mutableStateOf("openai-completions") }
    var customModels by remember { mutableStateOf("") }
    var customFormError by remember { mutableStateOf<String?>(null) }

    val models by remember(localConfig.aiProvider, localConfig.customAiProviders) {
        derivedStateOf {
            configManager.getAvailableModels()[localConfig.aiProvider]
                ?: localConfig.customAiProviders
                    .firstOrNull { it.id == localConfig.aiProvider }
                    ?.models
                    ?.map { it to it }
                    .orEmpty()
        }
    }
    val needsApiKey by remember(localConfig.aiProvider) {
        derivedStateOf { configManager.providerRequiresApiKey(localConfig.aiProvider) }
    }
    val currentModelLabel by remember(localConfig.aiModel, localConfig.aiProvider, models) {
        derivedStateOf { models.firstOrNull { it.first == localConfig.aiModel }?.second ?: localConfig.aiModel }
    }

    val scrollState = rememberScrollState()
    val fieldColors = settingsFieldColors()

    fun beginCustomEdit(provider: CustomAiProvider) {
        editingCustomId = provider.id
        customId = provider.id
        customName = provider.name
        customBaseUrl = provider.baseUrl
        customApiKey = provider.apiKey
        customApi = provider.api
        customModels = provider.models.joinToString(", ")
        customFormError = null
    }

    fun resetCustomForm() {
        editingCustomId = null
        customId = ""
        customName = ""
        customBaseUrl = ""
        customApiKey = ""
        customApi = "openai-completions"
        customModels = ""
        customFormError = null
    }

    fun saveCustomProvider() {
        val normalizedId = customId.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        val name = customName.trim()
        val baseUrl = customBaseUrl.trim().trimEnd('/')
        val modelList = customModels.split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        when {
            normalizedId.isBlank() -> customFormError = "Provider ID is required."
            normalizedId in builtInProviders -> customFormError = "Choose a different ID; this name is reserved."
            name.isBlank() -> customFormError = "Provider name is required."
            baseUrl.isBlank() -> customFormError = "Base URL is required."
            !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                customFormError = "Base URL must start with http:// or https://."
            modelList.isEmpty() -> customFormError = "Add at least one model ID."
            else -> {
                val provider = CustomAiProvider(
                    id = normalizedId,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = customApiKey.trim(),
                    api = customApi,
                    models = modelList
                )
                val updated = localConfig.customAiProviders
                    .filterNot { it.id == editingCustomId || it.id == normalizedId } + provider
                localConfig = localConfig.copy(
                    customAiProviders = updated,
                    aiProvider = normalizedId,
                    aiApiKey = provider.apiKey,
                    aiModel = modelList.first()
                )
                configManager.updateConfig(localConfig)
                resetCustomForm()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
    ) {
        TopAppBar(
            title = { Text("Settings", color = ClawTextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClawTextPrimary)
                }
            },
            actions = {
                TextButton(onClick = {
                    val users = telegramUserInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val activeCustom = localConfig.customAiProviders.firstOrNull { it.id == localConfig.aiProvider }
                    val configToSave = if (activeCustom != null) {
                        localConfig.copy(
                            telegramAllowedUsers = users,
                            customAiProviders = localConfig.customAiProviders.map {
                                if (it.id == activeCustom.id) it.copy(apiKey = localConfig.aiApiKey) else it
                            }
                        )
                    } else {
                        localConfig.copy(telegramAllowedUsers = users)
                    }
                    configManager.updateConfig(configToSave)
                    onSave()
                }) {
                    Text("Save", color = ClawGreen, fontWeight = FontWeight.Bold)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            SectionHeader(icon = Icons.Default.Psychology, title = "AI Model")

            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it }
            ) {
                OutlinedTextField(
                    value = configManager.getProviderName(localConfig.aiProvider),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("AI Provider", color = ClawTextSecondary) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = fieldColors
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(configManager.getProviderName(provider)) },
                            onClick = {
                                val custom = localConfig.customAiProviders.firstOrNull { it.id == provider }
                                val firstModel = configManager.getAvailableModels()[provider]?.firstOrNull()?.first
                                    ?: custom?.models?.firstOrNull()
                                    ?: ""
                                localConfig = localConfig.copy(
                                    aiProvider = provider,
                                    aiApiKey = custom?.apiKey ?: localConfig.aiApiKey,
                                    aiModel = firstModel
                                )
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!needsApiKey) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClawGreen.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = ClawGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Sign in with your Google account. No API key needed.", fontSize = 13.sp, color = ClawGreen)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (needsApiKey) {
                OutlinedTextField(
                    value = localConfig.aiApiKey,
                    onValueChange = { localConfig = localConfig.copy(aiApiKey = it) },
                    label = { Text("API Key", color = ClawTextSecondary) },
                    placeholder = { Text(configManager.getProviderKeyHint(localConfig.aiProvider), color = ClawTextSecondary.copy(alpha = 0.3f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = ClawTextSecondary)
                        }
                    },
                    colors = fieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = currentModelLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model", color = ClawTextSecondary) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = fieldColors
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    models.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name, color = when {
                                name.contains("Free") -> ClawGreen
                                name.contains("Best") -> ClawYellow
                                name.contains("Fast") -> ClawBlue
                                name.contains("Cheap") -> ClawOrange
                                else -> LocalContentColor.current
                            }) },
                            onClick = {
                                localConfig = localConfig.copy(aiModel = id)
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Default.Extension, title = "Custom AI Providers")
            Text(
                "Add any OpenAI-compatible service by entering its Base URL, API key, and model IDs. The provider is written to OpenClaw's models.providers configuration.",
                fontSize = 12.sp,
                color = ClawTextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it }
            ) {
                OutlinedTextField(
                    value = "Choose a popular provider preset",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Popular providers", color = ClawTextSecondary) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = fieldColors
                )
                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false }
                ) {
                    popularPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                customId = preset.id
                                customName = preset.name
                                customBaseUrl = preset.baseUrl
                                customApi = preset.api
                                customModels = preset.models.joinToString(", ")
                                customApiKey = ""
                                editingCustomId = null
                                customFormError = null
                                presetExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = customId,
                onValueChange = { customId = it },
                label = { Text("Provider ID", color = ClawTextSecondary) },
                placeholder = { Text("my-provider", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("Provider name", color = ClawTextSecondary) },
                placeholder = { Text("My AI Provider", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customBaseUrl,
                onValueChange = { customBaseUrl = it },
                label = { Text("Base URL", color = ClawTextSecondary) },
                placeholder = { Text("https://api.example.com/v1", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customApiKey,
                onValueChange = { customApiKey = it },
                label = { Text("Provider API key (optional for local services)", color = ClawTextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showCustomApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showCustomApiKey = !showCustomApiKey }) {
                        Icon(if (showCustomApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = ClawTextSecondary)
                    }
                },
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customModels,
                onValueChange = { customModels = it },
                label = { Text("Model IDs (comma separated)", color = ClawTextSecondary) },
                placeholder = { Text("model-a, model-b", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customApi,
                onValueChange = { customApi = it },
                label = { Text("API format", color = ClawTextSecondary) },
                placeholder = { Text("openai-completions or anthropic-messages", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors
            )
            if (customFormError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(customFormError!!, color = ClawRed, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { saveCustomProvider() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
                ) {
                    Icon(if (editingCustomId == null) Icons.Default.Add else Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (editingCustomId == null) "Add provider" else "Update provider")
                }
                if (editingCustomId != null) {
                    OutlinedButton(
                        onClick = { resetCustomForm() },
                        modifier = Modifier.weight(0.7f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Cancel") }
                }
            }

            if (localConfig.customAiProviders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                localConfig.customAiProviders.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ClawCardBg, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.name, color = ClawTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${provider.id} • ${provider.models.size} model(s)", color = ClawTextSecondary, fontSize = 11.sp)
                            Text(provider.baseUrl, color = ClawBlue, fontSize = 10.sp, maxLines = 1)
                        }
                        IconButton(onClick = { beginCustomEdit(provider) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit provider", tint = ClawBlue)
                        }
                        IconButton(onClick = {
                            val updated = localConfig.customAiProviders.filterNot { it.id == provider.id }
                            localConfig = if (localConfig.aiProvider == provider.id) {
                                localConfig.copy(
                                    customAiProviders = updated,
                                    aiProvider = "openrouter",
                                    aiApiKey = "",
                                    aiModel = configManager.getAvailableModels()["openrouter"]?.firstOrNull()?.first.orEmpty()
                                )
                            } else localConfig.copy(customAiProviders = updated)
                            configManager.updateConfig(localConfig)
                            if (editingCustomId == provider.id) resetCustomForm()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete provider", tint = ClawRed)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Default.Send, title = "Telegram Bot")
            OutlinedTextField(
                value = localConfig.telegramBotToken,
                onValueChange = { localConfig = localConfig.copy(telegramBotToken = it) },
                label = { Text("Bot Token", color = ClawTextSecondary) },
                placeholder = { Text("123456:ABC-DEF1234...", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showTelegramToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showTelegramToken = !showTelegramToken }) {
                        Icon(if (showTelegramToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = ClawTextSecondary)
                    }
                },
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = telegramUserInput,
                onValueChange = { telegramUserInput = it },
                label = { Text("Allowed User IDs (comma separated)", color = ClawTextSecondary) },
                placeholder = { Text("123456789, 987654321", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Get your User ID from @userinfobot on Telegram", fontSize = 11.sp, color = ClawTextSecondary.copy(alpha = 0.5f))

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Default.Forum, title = "Discord Bot (Optional)")
            OutlinedTextField(
                value = localConfig.discordBotToken,
                onValueChange = { localConfig = localConfig.copy(discordBotToken = it) },
                label = { Text("Discord Bot Token", color = ClawTextSecondary) },
                placeholder = { Text("MTIzNDU2Nzg5...", color = ClawTextSecondary.copy(alpha = 0.3f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showDiscordToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showDiscordToken = !showDiscordToken }) {
                        Icon(if (showDiscordToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = ClawTextSecondary)
                    }
                },
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Default.Dns, title = "Server")
            OutlinedTextField(
                value = localConfig.port.toString(),
                onValueChange = {
                    val p = it.toIntOrNull()
                    if (p != null && p in 1..65535) localConfig = localConfig.copy(port = p)
                },
                label = { Text("Port", color = ClawTextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Default.Security, title = "Root Access")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (localConfig.rootEnabled) ClawRed.copy(alpha = 0.1f) else ClawCardBg, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Root Access", fontSize = 14.sp, color = ClawTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        if (localConfig.rootEnabled) "Root access is enabled for commands."
                        else "Disabled — AI uses normal permissions",
                        fontSize = 11.sp,
                        color = if (localConfig.rootEnabled) ClawRed else ClawTextSecondary
                    )
                }
                Switch(
                    checked = localConfig.rootEnabled,
                    onCheckedChange = { localConfig = localConfig.copy(rootEnabled = it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = ClawRed, uncheckedTrackColor = ClawCardBgLight)
                )
            }

            if (localConfig.rootEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().background(ClawYellow.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding(12.dp)
                ) {
                    Text("Root access gives AI full system control. All root commands are logged.", fontSize = 11.sp, color = ClawYellow)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(ClawBlue.copy(alpha = 0.1f), RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = ClawBlue, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Settings are saved to openclaw.json. API keys for built-in providers are also mirrored to .env. Restart OpenClaw after changing settings.",
                    fontSize = 12.sp, color = ClawTextSecondary
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
        Icon(icon, contentDescription = null, tint = ClawRed, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ClawTextPrimary,
    unfocusedTextColor = ClawTextPrimary,
    focusedBorderColor = ClawRed,
    unfocusedBorderColor = ClawTextSecondary.copy(alpha = 0.3f),
    cursorColor = ClawRed,
    focusedLabelColor = ClawRed,
)
