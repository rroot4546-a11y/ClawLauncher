package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: String,
    onBack: () -> Unit,
    onClear: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom
    LaunchedEffect(logs) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
    ) {
        TopAppBar(
            title = { Text("Logs", color = ClawTextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClawTextPrimary)
                }
            },
            actions = {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = ClawTextSecondary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        if (logs.isBlank()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = ClawTextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("No logs yet", color = ClawTextSecondary)
                Text("Start OpenClaw to see output", fontSize = 12.sp, color = ClawTextSecondary.copy(alpha = 0.5f))
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                color = ClawCardBg
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    logs.lines().forEach { line ->
                        val color = when {
                            line.startsWith("✓") || line.startsWith("✅") -> ClawGreen
                            line.startsWith("❌") || line.contains("error", true) -> ClawRed
                            line.startsWith("→") -> ClawBlue
                            line.contains("warn", true) -> ClawYellow
                            else -> ClawTextSecondary
                        }
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
