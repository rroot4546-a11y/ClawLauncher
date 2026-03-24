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
import com.roox.clawlauncher.engine.ConfigManager
import com.roox.clawlauncher.engine.ProcessManager
import com.roox.clawlauncher.engine.ServerState
import com.roox.clawlauncher.ui.screens.*
import com.roox.clawlauncher.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var processManager: ProcessManager
    private lateinit var bootstrapManager: BootstrapManager
    private lateinit var backupManager: BackupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configManager = ConfigManager(this)
        processManager = ProcessManager(this, configManager)
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
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = ClawDarkBg,
                        contentColor = ClawTextPrimary
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Control") }
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
                            onSettings = { currentScreen = "settings" },
                            onLogs = { currentScreen = "logs" },
                            onGoToOpenClaw = {
                                try {
                                    val port = serverStatus.port
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:$port"))
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "Cannot open browser", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onReset = {
                                // Show confirmation
                                lifecycleScope.launch {
                                    processManager.stop()
                                    val baseDir = configManager.getBaseDir()
                                    baseDir.deleteRecursively()
                                    baseDir.mkdirs()
                                    Toast.makeText(this@MainActivity, "App data reset. Restart the app.", Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                        1 -> ToolkitScreen(
                            onSkillStore = {
                                Toast.makeText(this@MainActivity, "Skill Store coming in v2.0", Toast.LENGTH_SHORT).show()
                            },
                            onSkillsManager = {
                                Toast.makeText(this@MainActivity, "Skills Manager coming in v2.0", Toast.LENGTH_SHORT).show()
                            },
                            onBackupRestore = { currentScreen = "backup" },
                            onSnapshotConfig = { currentScreen = "settings" }
                        )
                    }
                }
            }
            "setup" -> SetupScreen(
                progress = setupProgress,
                isNodeInstalled = bootstrapManager.isNodeInstalled,
                isOpenClawInstalled = bootstrapManager.isOpenClawInstalled,
                onStartSetup = { lifecycleScope.launch { bootstrapManager.runSetup() } },
                onUpdate = { lifecycleScope.launch { bootstrapManager.updateOpenClaw() } },
                onBack = { currentScreen = "main" }
            )
            "settings" -> SettingsScreen(
                configManager = configManager,
                onBack = { currentScreen = "main" },
                onSave = {
                    lifecycleScope.launch {
                        configManager.saveConfig()
                        Toast.makeText(this@MainActivity, "Settings saved ✓", Toast.LENGTH_SHORT).show()
                        currentScreen = "main"
                    }
                }
            )
            "status" -> StatusInfoScreen(
                info = processManager.getStatusInfo(),
                onBack = { currentScreen = "main" }
            )
            "logs" -> LogsScreen(
                logs = processManager.getLogs(),
                onBack = { currentScreen = "main" },
                onClear = { }
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
                        Toast.makeText(this@MainActivity, "Rolled back ✓", Toast.LENGTH_SHORT).show()
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
