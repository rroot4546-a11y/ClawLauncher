package com.roox.clawlauncher

import android.content.Context
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
import com.roox.clawlauncher.ads.AdBanner
import com.roox.clawlauncher.ads.AdManager
import com.roox.clawlauncher.engine.BackupManager
import com.roox.clawlauncher.engine.BootstrapManager
import com.roox.clawlauncher.engine.ConfigManager
import com.roox.clawlauncher.engine.ProcessManager
import com.roox.clawlauncher.service.BatteryHelper
import com.roox.clawlauncher.ui.screens.*
import com.roox.clawlauncher.ui.theme.*
import com.roox.clawlauncher.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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

        AdManager.initialize(this)

        // Show interstitial ad after 3 seconds
        lifecycleScope.launch {
            delay(3000)
            AdManager.showInterstitial(this@MainActivity)
        }

        // Request notification permission on start
        if (!PermissionHelper.hasNotificationPermission(this)) {
            PermissionHelper.requestNotificationPermission(this)
        }

        setContent {
            ClawLauncherTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ClawDarkBg)
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MainApp()
                    }
                    AdBanner()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh state when returning to the app (e.g., from browser or permission settings)
        processManager.refreshState()
    }

    @Composable
    fun MainApp() {
        val serverStatus by processManager.status.collectAsState()
        val setupProgress by bootstrapManager.progress.collectAsState()
        val updateInfo by bootstrapManager.updateInfo.collectAsState()
        val restorePoints by backupManager.restorePoints.collectAsState()
        var hasStoragePerm by remember { mutableStateOf(PermissionHelper.hasStoragePermission(this@MainActivity)) }

        // Re-check permission on recomposition
        LaunchedEffect(Unit) {
            hasStoragePerm = PermissionHelper.hasStoragePermission(this@MainActivity)
        }

        var currentScreen by remember { mutableStateOf("main") }
        var selectedTab by remember { mutableIntStateOf(0) }

        when (currentScreen) {
            "main" -> {
                // Force refresh when returning to main screen
                LaunchedEffect(Unit) {
                    processManager.refreshState()
                }

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
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Control") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Toolkit") })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Files") })
                    }

                    val prefs = this@MainActivity.getSharedPreferences("claw_prefs", Context.MODE_PRIVATE)
                    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start_on_boot", false)) }
                    var isBatteryOptimized by remember { mutableStateOf(!BatteryHelper.isIgnoringBatteryOptimizations(this@MainActivity)) }

                    when (selectedTab) {
                        0 -> ControlPanelScreen(
                            status = serverStatus,
                            onStart = { lifecycleScope.launch { processManager.start() } },
                            onStop = { lifecycleScope.launch { processManager.stop() } },
                            onRestart = { lifecycleScope.launch { processManager.restart() } },
                            onSetup = { currentScreen = "setup" },
                            onStatusInfo = { currentScreen = "status" },
                            onSettings = { currentScreen = "settings" },
                            onLogs = { currentScreen = "logs" },
                            onGoToOpenClaw = {
                                try {
                                    val port = serverStatus.port
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:$port")))
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "Cannot open browser", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onReset = {
                                lifecycleScope.launch {
                                    processManager.stop()
                                    configManager.baseDir.deleteRecursively()
                                    configManager.baseDir.mkdirs()
                                    processManager.refreshState()
                                    Toast.makeText(this@MainActivity, "App data reset. Restart the app.", Toast.LENGTH_LONG).show()
                                }
                            },
                            onRequestBatteryOptimization = {
                                BatteryHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
                                // Will re-check on resume
                            },
                            autoStartOnBoot = autoStart,
                            onAutoStartToggle = { enabled ->
                                autoStart = enabled
                                prefs.edit().putBoolean("auto_start_on_boot", enabled).apply()
                            },
                            isBatteryOptimized = isBatteryOptimized
                        )
                        1 -> ToolkitScreen(
                            onSkillStore = { Toast.makeText(this@MainActivity, "Skill Store coming soon", Toast.LENGTH_SHORT).show() },
                            onSkillsManager = { Toast.makeText(this@MainActivity, "Skills Manager coming soon", Toast.LENGTH_SHORT).show() },
                            onBackupRestore = { currentScreen = "backup" },
                            onSnapshotConfig = { currentScreen = "settings" }
                        )
                        2 -> FileManagerScreen(
                            hasPermission = hasStoragePerm,
                            onRequestPermission = {
                                PermissionHelper.requestStoragePermission(this@MainActivity)
                                // Will recheck on resume
                            },
                            onBack = { selectedTab = 0 }
                        )
                    }
                }
            }
            "setup" -> SetupScreen(
                progress = setupProgress,
                isNodeInstalled = bootstrapManager.isNodeInstalled,
                isOpenClawInstalled = bootstrapManager.isOpenClawInstalled,
                updateInfo = updateInfo,
                onStartSetup = { lifecycleScope.launch { bootstrapManager.runSetup() } },
                onCheckUpdates = { lifecycleScope.launch { bootstrapManager.checkForUpdates() } },
                onUpdate = { lifecycleScope.launch { bootstrapManager.updateOpenClaw() } },
                onBack = {
                    // Force re-check when returning from setup
                    processManager.refreshState()
                    currentScreen = "main"
                }
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
                    lifecycleScope.launch { backupManager.createRestorePoint(name, desc) }
                },
                onRollback = { id ->
                    lifecycleScope.launch {
                        backupManager.rollback(id)
                        Toast.makeText(this@MainActivity, "Rolled back ✓", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { id ->
                    lifecycleScope.launch { backupManager.deleteRestorePoint(id) }
                }
            )
        }
    }
}
