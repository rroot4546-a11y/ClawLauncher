package com.roox.clawlauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.roox.clawlauncher.engine.BackupManager
import com.roox.clawlauncher.engine.BootstrapManager
import com.roox.clawlauncher.engine.ProcessManager
import com.roox.clawlauncher.engine.ServerState
import com.roox.clawlauncher.ui.screens.*
import com.roox.clawlauncher.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var processManager: ProcessManager
    private lateinit var bootstrapManager: BootstrapManager
    private lateinit var backupManager: BackupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        processManager = ProcessManager(this)
        bootstrapManager = BootstrapManager(this)
        backupManager = BackupManager(this)

        setContent {
            ClawLauncherTheme {
                MainApp()
            }
        }
    }

    @Composable
    fun MainApp() {
        val serverStatus by processManager.status.collectAsState()
        val setupProgress by bootstrapManager.progress.collectAsState()
        val restorePoints by backupManager.restorePoints.collectAsState()

        var currentScreen by remember { mutableStateOf("main") }
        var selectedTab by remember { mutableIntStateOf(0) }

        when (currentScreen) {
            "main" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ClawDarkBg)
                ) {
                    // Tab row
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = ClawDarkBg,
                        contentColor = ClawTextPrimary
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Basic Functions") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Toolkit") }
                        )
                    }

                    when (selectedTab) {
                        0 -> ControlPanelScreen(
                            status = serverStatus,
                            onStart = {
                                lifecycleScope.launch { processManager.start() }
                            },
                            onStop = {
                                lifecycleScope.launch { processManager.stop() }
                            },
                            onRestart = {
                                lifecycleScope.launch { processManager.restart() }
                            },
                            onSetup = { currentScreen = "setup" },
                            onStatusInfo = { currentScreen = "status" },
                            onGoToOpenClaw = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${serverStatus.port}"))
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "Cannot open browser", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onReset = {
                                Toast.makeText(this@MainActivity, "Reset functionality coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                        1 -> ToolkitScreen(
                            onSkillStore = {
                                Toast.makeText(this@MainActivity, "Skill Store coming soon", Toast.LENGTH_SHORT).show()
                            },
                            onSkillsManager = {
                                Toast.makeText(this@MainActivity, "Skills Manager coming soon", Toast.LENGTH_SHORT).show()
                            },
                            onBackupRestore = { currentScreen = "backup" },
                            onSnapshotConfig = {
                                Toast.makeText(this@MainActivity, "Config Snapshot coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            "setup" -> SetupScreen(
                progress = setupProgress,
                onStartSetup = { lifecycleScope.launch { bootstrapManager.runSetup() } },
                onBack = { currentScreen = "main" }
            )
            "status" -> StatusInfoScreen(
                info = processManager.getStatusInfo(),
                onBack = { currentScreen = "main" }
            )
            "backup" -> BackupScreen(
                restorePoints = restorePoints,
                onBack = { currentScreen = "main" },
                onCreateBackup = { name, desc ->
                    lifecycleScope.launch {
                        backupManager.createRestorePoint(name, desc)
                    }
                },
                onRollback = { id ->
                    lifecycleScope.launch {
                        backupManager.rollback(id)
                        Toast.makeText(this@MainActivity, "Rolled back successfully", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { id ->
                    lifecycleScope.launch {
                        backupManager.deleteRestorePoint(id)
                    }
                }
            )
        }
    }
}
