package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.roox.clawlauncher.engine.RestorePoint
import com.roox.clawlauncher.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    restorePoints: List<RestorePoint>,
    onBack: () -> Unit,
    onCreateBackup: (String, String) -> Unit,
    onRollback: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var backupName by remember { mutableStateOf("") }
    var backupDesc by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
    ) {
        // Top bar
        TopAppBar(
            title = { Text("Backup Restore Points", color = ClawTextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClawTextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // Create button
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Restore Point", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (restorePoints.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = ClawTextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No restore points yet", color = ClawTextSecondary)
                    Text("Create one before making big changes", fontSize = 12.sp, color = ClawTextSecondary.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(restorePoints) { point ->
                        RestorePointCard(
                            point = point,
                            onRollback = { onRollback(point.id) },
                            onDelete = { onDelete(point.id) }
                        )
                    }
                }
            }
        }
    }

    // Create backup dialog
    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ClawCardBg
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("New Restore Point", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = backupName,
                        onValueChange = { backupName = it },
                        label = { Text("Name", color = ClawTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ClawTextPrimary,
                            unfocusedTextColor = ClawTextPrimary,
                            focusedBorderColor = ClawRed,
                            unfocusedBorderColor = ClawTextSecondary.copy(alpha = 0.3f)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = backupDesc,
                        onValueChange = { backupDesc = it },
                        label = { Text("Description (Optional)", color = ClawTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ClawTextPrimary,
                            unfocusedTextColor = ClawTextPrimary,
                            focusedBorderColor = ClawRed,
                            unfocusedBorderColor = ClawTextSecondary.copy(alpha = 0.3f)
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel", color = ClawTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (backupName.isNotBlank()) {
                                    onCreateBackup(backupName, backupDesc)
                                    backupName = ""
                                    backupDesc = ""
                                    showDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClawGreen)
                        ) {
                            Text("Backup Now")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RestorePointCard(
    point: RestorePoint,
    onRollback: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ClawCardBg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(point.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
            if (point.description.isNotBlank()) {
                Text(point.description, fontSize = 13.sp, color = ClawTextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${point.date} • ${"%.2f".format(point.sizeMb)} MB", fontSize = 12.sp, color = ClawTextSecondary.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRollback,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
                ) {
                    Text("Roll Back", fontSize = 13.sp)
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClawCardBgLight)
                ) {
                    Text("Delete", fontSize = 13.sp)
                }
            }
        }
    }
}
