package com.roox.clawlauncher.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.engine.SetupProgress
import com.roox.clawlauncher.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    progress: SetupProgress,
    isNodeInstalled: Boolean,
    isOpenClawInstalled: Boolean,
    onStartSetup: () -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit
) {
    val animatedProgress by animateFloatAsState(targetValue = progress.progress, label = "setup_progress")
    val logScrollState = rememberScrollState()

    // Auto-scroll log
    LaunchedEffect(progress.log) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
    ) {
        TopAppBar(
            title = { Text("Setup", color = ClawTextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClawTextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!progress.isRunning && !progress.isComplete && progress.error == null) {
                // Initial state — show what's installed
                Icon(Icons.Default.Download, contentDescription = null, tint = ClawRed, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text("Setup OpenClaw", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)

                Spacer(modifier = Modifier.height(16.dp))

                // Status checklist
                StatusCheckItem("Node.js Runtime", isNodeInstalled)
                StatusCheckItem("OpenClaw Server", isOpenClawInstalled)

                Spacer(modifier = Modifier.height(16.dp))

                if (!isNodeInstalled || !isOpenClawInstalled) {
                    Text(
                        "This will download and install:\n• Node.js v20 LTS (~25 MB)\n• OpenClaw latest (~50 MB)\n\nRequires internet connection.",
                        fontSize = 13.sp,
                        color = ClawTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onStartSetup,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Install Now", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Everything is installed! ✅", fontSize = 15.sp, color = ClawGreen)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onUpdate,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = ClawBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update OpenClaw", color = ClawBlue)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
                    ) {
                        Text("Back to Control Panel", fontWeight = FontWeight.Bold)
                    }
                }

            } else if (progress.isRunning) {
                // Installing — show progress + live log
                CircularProgressIndicator(color = ClawRed, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(progress.step, fontSize = 15.sp, color = ClawTextPrimary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = ClawRed,
                    trackColor = ClawCardBg,
                )
                Text("${(animatedProgress * 100).toInt()}%", fontSize = 13.sp, color = ClawTextSecondary)

                Spacer(modifier = Modifier.height(16.dp))

                // Live log output
                if (progress.log.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = ClawCardBg
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(logScrollState)
                                .padding(10.dp)
                        ) {
                            progress.log.lines().forEach { line ->
                                val color = when {
                                    line.startsWith("✓") -> ClawGreen
                                    line.startsWith("❌") -> ClawRed
                                    line.startsWith("→") -> ClawBlue
                                    else -> ClawTextSecondary
                                }
                                Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = color)
                            }
                        }
                    }
                }

            } else if (progress.isComplete) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ClawGreen, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text("Setup Complete! 🦀", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawGreen)
                Spacer(modifier = Modifier.height(8.dp))
                Text("OpenClaw is ready to launch", fontSize = 14.sp, color = ClawTextSecondary)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
                ) {
                    Text("Back to Control Panel", fontWeight = FontWeight.Bold)
                }

            } else if (progress.error != null) {
                Icon(Icons.Default.Error, contentDescription = null, tint = ClawRed, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text("Setup Failed", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawRed)
                Spacer(modifier = Modifier.height(8.dp))
                Text(progress.error!!, fontSize = 13.sp, color = ClawTextSecondary, textAlign = TextAlign.Center)

                // Show log for debugging
                if (progress.log.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = ClawCardBg
                    ) {
                        Column(modifier = Modifier.verticalScroll(logScrollState).padding(10.dp)) {
                            progress.log.lines().forEach { line ->
                                Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ClawTextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onStartSetup,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
                ) {
                    Text("Retry", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatusCheckItem(label: String, installed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (installed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (installed) ClawGreen else ClawTextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = if (installed) ClawGreen else ClawTextSecondary, fontSize = 14.sp)
    }
}
