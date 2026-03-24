package com.roox.clawlauncher.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onGoToOpenClaw: () -> Unit,
    onReset: () -> Unit
) {
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
            // Claw Logo
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = ClawRed.copy(alpha = 0.2f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🦀", fontSize = 24.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ClawLauncher", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
                Text("OpenClaw for Android", fontSize = 13.sp, color = ClawTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status indicator
        Text("Control Panel", fontSize = 14.sp, color = ClawTextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        StatusIndicator(status)

        Spacer(modifier = Modifier.height(24.dp))

        // Main action button
        MainActionButton(
            status = status,
            onStart = onStart,
            onGoToOpenClaw = onGoToOpenClaw,
            onSetup = onSetup
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Grid buttons (2x2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GridButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Settings,
                label = "Setup",
                onClick = onSetup
            )
            GridButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Info,
                label = "Status",
                onClick = onStatusInfo
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GridButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Refresh,
                label = "Restart",
                onClick = onRestart,
                enabled = status.state == ServerState.RUNNING
            )
            GridButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Stop,
                label = "Stop",
                onClick = onStop,
                enabled = status.state == ServerState.RUNNING || status.state == ServerState.STARTING
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Reset button at bottom
        TextButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = ClawRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset This App", color = ClawRed)
        }
    }
}

@Composable
fun StatusIndicator(status: ServerStatus) {
    val (dotColor, text) = when (status.state) {
        ServerState.RUNNING -> ClawGreen to "OpenClaw is Live"
        ServerState.STARTING -> ClawYellow to "Starting..."
        ServerState.STOPPING -> ClawYellow to "Stopping..."
        ServerState.STOPPED -> ClawRed to "OpenClaw Not Running"
        ServerState.ERROR -> ClawRed to "Error: ${status.message}"
        ServerState.NOT_INSTALLED -> ClawOrange to "Setup Required"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
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
        ServerState.STOPPED, ServerState.ERROR -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start OpenClaw", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        ServerState.STARTING -> {
            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawCardBgLight)
            ) {
                CircularProgressIndicator(
                    color = ClawTextPrimary,
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Initializing OpenClaw...", fontSize = 14.sp, color = ClawTextSecondary)
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
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ClawCardBg,
            disabledContainerColor = ClawCardBg.copy(alpha = 0.5f)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon, contentDescription = null,
                tint = if (enabled) ClawTextPrimary else ClawTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                fontSize = 12.sp,
                color = if (enabled) ClawTextPrimary else ClawTextSecondary.copy(alpha = 0.5f)
            )
        }
    }
}
