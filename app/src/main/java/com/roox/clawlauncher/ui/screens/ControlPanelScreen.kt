package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.engine.ServerState
import com.roox.clawlauncher.engine.ServerStatus
import com.roox.clawlauncher.ui.theme.*

@Composable
fun ControlPanelScreen(
    status: ServerStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSetup: () -> Unit,
    onStatusInfo: () -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    onGoToOpenClaw: () -> Unit,
    onReset: () -> Unit,
    onRequestBatteryOptimization: () -> Unit = {},
    autoStartOnBoot: Boolean = false,
    onAutoStartToggle: (Boolean) -> Unit = {},
    isBatteryOptimized: Boolean = true
) {
    // Derive state values to avoid recomposition of the whole tree
    val isRunning by remember { derivedStateOf { status.state == ServerState.RUNNING } }
    val isNotInstalled by remember { derivedStateOf { status.state == ServerState.NOT_INSTALLED } }
    val canStop by remember { derivedStateOf { status.state == ServerState.RUNNING || status.state == ServerState.STARTING } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ClawRed.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("\uD83E\uDD80", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ClawLauncher", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
                Text("OpenClaw for Android", fontSize = 13.sp, color = ClawTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Control Panel", fontSize = 14.sp, color = ClawTextSecondary)
        Spacer(modifier = Modifier.height(4.dp))

        // Status
        StatusIndicator(status)

        Spacer(modifier = Modifier.height(12.dp))

        // Info card (version, disk, port)
        if (!isNotInstalled) {
            InfoCard(status)
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Main action button
        MainActionButton(status, onStart, onGoToOpenClaw, onSetup)

        // Open Chat button when running
        if (isRunning) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onGoToOpenClaw,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ClawBlue)
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = ClawBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Chat in Browser", fontSize = 14.sp, color = ClawBlue, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Grid 2x3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GridButton(Modifier.weight(1f), Icons.Default.Download, "Setup", onSetup)
            GridButton(Modifier.weight(1f), Icons.Default.Info, "Status", onStatusInfo)
            GridButton(Modifier.weight(1f), Icons.Default.Settings, "Settings", onSettings)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GridButton(
                Modifier.weight(1f), Icons.Default.Refresh, "Restart", onRestart,
                enabled = isRunning
            )
            GridButton(
                Modifier.weight(1f), Icons.Default.Stop, "Stop", onStop,
                enabled = canStop
            )
            GridButton(Modifier.weight(1f), Icons.Default.Terminal, "Logs", onLogs)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Running indicator at bottom
        if (isRunning) {
            val uptimeText by remember(status.uptime) {
                derivedStateOf {
                    val secs = status.uptime
                    when {
                        secs < 60 -> "${secs}s"
                        secs < 3600 -> "${secs / 60}m ${secs % 60}s"
                        else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawGreen.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ClawGreen))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Running on port ${status.port}", fontSize = 12.sp, color = ClawGreen)
                Spacer(modifier = Modifier.weight(1f))
                if (status.uptime > 0) {
                    Text("\u23F1 $uptimeText", fontSize = 11.sp, color = ClawGreen.copy(alpha = 0.7f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Background settings
        BackgroundSettingsCard(
            onRequestBatteryOptimization = onRequestBatteryOptimization,
            autoStartOnBoot = autoStartOnBoot,
            onAutoStartToggle = onAutoStartToggle,
            isBatteryOptimized = isBatteryOptimized
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = ClawRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset This App", color = ClawRed)
        }
    }
}

@Composable
fun InfoCard(status: ServerStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClawCardBg, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        InfoItem(label = "Version", value = status.openclawVersion ?: "\u2014")
        InfoItem(label = "Port", value = status.port.toString())
        InfoItem(label = "Disk", value = status.diskUsage.ifBlank { "\u2014" })
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = ClawTextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, color = ClawTextPrimary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatusIndicator(status: ServerStatus) {
    val (dotColor, text) = remember(status.state) {
        when (status.state) {
            ServerState.RUNNING -> ClawGreen to "OpenClaw is Live"
            ServerState.STARTING -> ClawYellow to "Starting..."
            ServerState.STOPPING -> ClawYellow to "Stopping..."
            ServerState.STOPPED -> ClawGreen to "Ready to Start"
            ServerState.ERROR -> ClawRed to "Error"
            ServerState.NOT_INSTALLED -> ClawOrange to "Setup Required"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, color = dotColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MainActionButton(
    status: ServerStatus,
    onStart: () -> Unit,
    onGoToOpenClaw: () -> Unit,
    onSetup: () -> Unit
) {
    when (status.state) {
        ServerState.STOPPED -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start OpenClaw", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        ServerState.ERROR -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry Start", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        ServerState.STARTING -> {
            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawCardBgLight)
            ) {
                CircularProgressIndicator(color = ClawTextPrimary, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Starting...", fontSize = 14.sp, color = ClawTextSecondary)
            }
        }
        ServerState.RUNNING -> {
            Button(
                onClick = onGoToOpenClaw,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Go to OpenClaw", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        ServerState.NOT_INSTALLED -> {
            Button(
                onClick = onSetup,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawOrange)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setup OpenClaw", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        else -> { }
    }
}

@Composable
fun GridButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val tint = if (enabled) ClawTextPrimary else ClawTextSecondary.copy(alpha = 0.5f)
    Button(
        onClick = onClick,
        modifier = modifier.height(68.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ClawCardBg,
            disabledContainerColor = ClawCardBg.copy(alpha = 0.5f)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, color = tint)
        }
    }
}

@Composable
fun BackgroundSettingsCard(
    onRequestBatteryOptimization: () -> Unit,
    autoStartOnBoot: Boolean,
    onAutoStartToggle: (Boolean) -> Unit,
    isBatteryOptimized: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClawCardBg, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("⚙️ Background", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        // Battery optimization
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Battery Optimization", fontSize = 12.sp, color = ClawTextPrimary)
                Text(
                    if (isBatteryOptimized) "⚠️ Enabled — may kill background" else "✅ Disabled — runs freely",
                    fontSize = 10.sp,
                    color = if (isBatteryOptimized) ClawYellow else ClawGreen
                )
            }
            if (isBatteryOptimized) {
                TextButton(onClick = onRequestBatteryOptimization) {
                    Text("Disable", fontSize = 11.sp, color = ClawBlue)
                }
            }
        }

        HorizontalDivider(color = ClawCardBgLight, modifier = Modifier.padding(vertical = 6.dp))

        // Auto-start on boot
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-start on boot", fontSize = 12.sp, color = ClawTextPrimary)
                Text("Start OpenClaw when phone restarts", fontSize = 10.sp, color = ClawTextSecondary)
            }
            Switch(
                checked = autoStartOnBoot,
                onCheckedChange = onAutoStartToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = ClawGreen)
            )
        }
    }
}
