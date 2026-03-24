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
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var telegramUserInput by remember { mutableStateOf(config.telegramAllowedUsers.joinToString(", ")) }

    val providers = listOf("openrouter", "google", "openai", "anthropic")
    val models = configManager.getAvailableModels()[localConfig.aiProvider] ?: emptyList()

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
                    configManager.updateConfig(localConfig.copy(telegramAllowedUsers = users))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ═══════════════════════════════════════
            // AI PROVIDER SECTION
            // ═══════════════════════════════════════
            SectionHeader(icon = Icons.Default.Psychology, title = "AI Model")

            // Provider dropdown
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
                    colors = settingsFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(configManager.getProviderName(provider)) },
                            onClick = {
                                localConfig = localConfig.copy(
                                    aiProvider = provider,
                                    aiModel = configManager.getAvailableModels()[provider]?.firstOrNull()?.first ?: ""
                                )
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // API Key
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
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null, tint = ClawTextSecondary
                        )
                    }
                },
                colors = settingsFieldColors()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Model dropdown
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = models.firstOrNull { it.first == localConfig.aiModel }?.second ?: localConfig.aiModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model", color = ClawTextSecondary) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = settingsFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    models.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                localConfig = localConfig.copy(aiModel = id)
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════
            // TELEGRAM SECTION
            // ═══════════════════════════════════════
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
                        Icon(
                            if (showTelegramToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null, tint = ClawTextSecondary
                        )
                    }
                },
                colors = settingsFieldColors()
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
                colors = settingsFieldColors()
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Get your User ID from @userinfobot on Telegram",
                fontSize = 11.sp, color = ClawTextSecondary.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════
            // DISCORD SECTION
            // ═══════════════════════════════════════
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
                        Icon(
                            if (showDiscordToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null, tint = ClawTextSecondary
                        )
                    }
                },
                colors = settingsFieldColors()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════
            // SERVER SECTION
            // ═══════════════════════════════════════
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
                colors = settingsFieldColors()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Info card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ClawBlue.copy(alpha = 0.1f)
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = ClawBlue, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Settings are saved to openclaw.json in the app's data directory. " +
                        "API keys are stored in .env file. Restart OpenClaw after changing settings.",
                        fontSize = 12.sp, color = ClawTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 12.dp)
    ) {
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
