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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roox.clawlauncher.ads.AdManager
import com.roox.clawlauncher.engine.BackupManager
import com.roox.clawlauncher.engine.BootstrapManager
import com.roox.clawlauncher.engine.ConfigManager
import com.roox.clawlauncher.engine.ProcessManager
import com.roox.clawlauncher.service.BatteryHelper
import com.roox.clawlauncher.ui.screens.*
import com.roox.clawlauncher.ui.theme.*
import com.roox.clawlauncher.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Managers are created up-front (cheap object construction) but any disk
    // I/O they perform during `init {}` is offloaded to a background coroutine
    // so the first frame paints immediately.
    private lateinit var configManager: ConfigManager
    private lateinit var processManager: ProcessManager
    private lateinit var bootstrapManager: BootstrapManager
    private lateinit var backupManager: BackupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the SplashScreen API BEFORE super.onCreate so the OS shows
        // our launcher icon immediately while the app process warms up.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        configManager = ConfigManager(this)
        processManager = ProcessManager(this, configManager)
        bootstrapManager = BootstrapManager(this)
        backupManager = BackupManager(this)

        // Render the UI ASAP. All heavy/idle work (AdMob init, ad loading,
        // first state refresh) is dispatched off the main thread so the
        // first frame is not blocked.
        setContent {
            ClawLauncherTheme {
                MainApp()
            }
        }

        // Defer non-UI work to after the first frame: AdMob init does
        // disk + network on a background thread, but its first call still
        // touches main-thread state on some devices.
        lifecycleScope.launch(Dispatchers.IO) {
            AdManager.initialize(this@MainActivity)
        }

        // Show interstitial ad after a longer delay so it never competes with
        // first paint or the user's first interaction.
        lifecycleScope.launch {
            // Wait until the activity has resumed at least once before doing
            // anything that could pause animation.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(8_000)
                AdManager.showInterstitial(this@MainActivity)
            }
        }

        // Permission prompt is very cheap but we still defer it so it doesn't
        // happen during the first frame's measure/layout pass.
        lifecycleScope.launch {
            if (!PermissionHelper.hasNotificationPermission(this@MainActivity)) {
                PermissionHelper.requestNotificationPermission(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh state when returning to the app (e.g., from browser or
        // permission settings). Done on a background dispatcher to avoid
        // any disk reads on the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            processManager.refreshState()
        }
    }

    @Composable
    fun MainApp() {
        // Hot state observed once at the top level; downstream composables read
        // only the slices they need so re-composition stays cheap.
        val serverStatus by processManager.status.collectAsState()
        val setupProgress by bootstrapManager.progress.collectAsState()
        val updateInfo by bootstrapManager.updateInfo.collectAsState()
        val restorePoints by backupManager.restorePoints.collectAsState()

        // SharedPreferences and battery-status are read once outside of
        // composition (no I/O on every recomposition). They are refreshed on
        // RESUME via the LaunchedEffect below.
        val prefs = remember {
            this@MainActivity.getSharedPreferences("claw_prefs", Context.MODE_PRIVATE)
        }
        var hasStoragePerm by remember {
            mutableStateOf(PermissionHelper.hasStoragePermission(this@MainActivity))
        }
        var autoStart by remember {
            mutableStateOf(prefs.getBoolean("auto_start_on_boot", false))
        }
        var isBatteryOptimized by remember {
            mutableStateOf(!BatteryHelper.isIgnoringBatteryOptimizations(this@MainActivity))
        }

        // Refresh ambient state cheaply once per RESUME (not on every
        // recomposition) by hooking into the lifecycle.
        LaunchedEffect(Unit) {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                hasStoragePerm = withContext(Dispatchers.IO) {
                    PermissionHelper.hasStoragePermission(this@MainActivity)
                }
                isBatteryOptimized = withContext(Dispatchers.IO) {
                    !BatteryHelper.isIgnoringBatteryOptimizations(this@MainActivity)
                }
            }
        }

        // Initial deferred state refresh — runs once after first composition
        // so the ProcessManager can discover the install state without
        // blocking the UI thread during onCreate.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { processManager.refreshState() }
        }

        var currentScreen by rememberSaveableScreen()
        var selectedTab by rememberSaveableTab()

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
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Control") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Toolkit") })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Files") })
                    }

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
                                    withContext(Dispatchers.IO) {
                                        configManager.baseDir.deleteRecursively()
                                        configManager.baseDir.mkdirs()
                                    }
                                    processManager.refreshState()
                                    Toast.makeText(this@MainActivity, "App data reset. Restart the app.", Toast.LENGTH_LONG).show()
                                }
                            },
                            onRequestBatteryOptimization = {
                                BatteryHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
                            },
                            autoStartOnBoot = autoStart,
                            onAutoStartToggle = { enabled ->
                                autoStart = enabled
                                lifecycleScope.launch(Dispatchers.IO) {
                                    prefs.edit().putBoolean("auto_start_on_boot", enabled).apply()
                                }
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
                    lifecycleScope.launch(Dispatchers.IO) { processManager.refreshState() }
                    currentScreen = "main"
                }
            )
            "settings" -> SettingsScreen(
                configManager = configManager,
                onBack = { currentScreen = "main" },
                onSave = {
                    lifecycleScope.launch {
                        configManager.saveConfig()
                        Toast.makeText(this@MainActivity, "Settings saved \u2713", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, "Rolled back \u2713", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { id ->
                    lifecycleScope.launch { backupManager.deleteRestorePoint(id) }
                }
            )
        }
    }
}

@Composable
private fun rememberSaveableScreen() = remember { mutableStateOf("main") }

@Composable
private fun rememberSaveableTab() = remember { mutableIntStateOf(0) }
