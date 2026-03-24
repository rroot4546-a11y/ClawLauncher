package com.roox.clawlauncher.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onStartSetup: () -> Unit,
    onBack: () -> Unit
) {
    val animatedProgress by animateFloatAsState(targetValue = progress.progress, label = "setup_progress")

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!progress.isRunning && !progress.isComplete && progress.error == null) {
                // Initial state
                Icon(Icons.Default.Download, contentDescription = null, tint = ClawRed, modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Setup OpenClaw", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This will download and install:\n• Node.js runtime\n• OpenClaw server\n• Required dependencies",
                    fontSize = 14.sp,
                    color = ClawTextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("≈ 50-100 MB download", fontSize = 12.sp, color = ClawTextSecondary.copy(alpha = 0.5f))

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onStartSetup,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Setup", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else if (progress.isRunning) {
                // Installing
                CircularProgressIndicator(
                    color = ClawRed,
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(progress.step, fontSize = 16.sp, color = ClawTextPrimary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = ClawRed,
                    trackColor = ClawCardBg,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("${(animatedProgress * 100).toInt()}%", fontSize = 14.sp, color = ClawTextSecondary)
            } else if (progress.isComplete) {
                // Done
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ClawGreen, modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Setup Complete!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawGreen)
                Spacer(modifier = Modifier.height(8.dp))
                Text("OpenClaw is ready to launch", fontSize = 14.sp, color = ClawTextSecondary)
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
                ) {
                    Text("Back to Control Panel", fontWeight = FontWeight.Bold)
                }
            } else if (progress.error != null) {
                // Error
                Icon(Icons.Default.Error, contentDescription = null, tint = ClawRed, modifier = Modifier.size(72.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Setup Failed", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ClawRed)
                Spacer(modifier = Modifier.height(8.dp))
                Text(progress.error!!, fontSize = 14.sp, color = ClawTextSecondary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(32.dp))
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
