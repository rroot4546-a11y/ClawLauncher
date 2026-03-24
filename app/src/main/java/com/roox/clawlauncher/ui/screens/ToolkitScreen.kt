package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.ui.theme.*

@Composable
fun ToolkitScreen(
    onSkillStore: () -> Unit,
    onSkillsManager: () -> Unit,
    onBackupRestore: () -> Unit,
    onSnapshotConfig: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
            .padding(20.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = ClawRed.copy(alpha = 0.2f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = ClawRed)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Toolkit", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
                Text("Give your OpenClaw SuperPowers", fontSize = 13.sp, color = ClawTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Menu items
        ToolkitMenuItem(
            icon = Icons.Default.ShoppingBag,
            title = "Skill Store",
            subtitle = "Find and install new tools",
            onClick = onSkillStore
        )

        Spacer(modifier = Modifier.height(12.dp))

        ToolkitMenuItem(
            icon = Icons.Default.Settings,
            title = "Skills Manager",
            subtitle = "Export and restore skills",
            onClick = onSkillsManager
        )

        Spacer(modifier = Modifier.height(12.dp))

        ToolkitMenuItem(
            icon = Icons.Default.Shield,
            title = "Backup Restore Points",
            subtitle = "In case something breaks…",
            onClick = onBackupRestore
        )

        Spacer(modifier = Modifier.height(12.dp))

        ToolkitMenuItem(
            icon = Icons.Default.SwapHoriz,
            title = "Snapshot Global Config",
            subtitle = "Hot-swap AI models, ports, & endpoints",
            onClick = onSnapshotConfig
        )
    }
}

@Composable
fun ToolkitMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = ClawCardBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = ClawTextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ClawTextPrimary)
                Text(subtitle, fontSize = 12.sp, color = ClawTextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ClawTextSecondary)
        }
    }
}
