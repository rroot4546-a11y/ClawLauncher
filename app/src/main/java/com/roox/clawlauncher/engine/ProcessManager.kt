package com.roox.clawlauncher.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.roox.clawlauncher.auth.GoogleAuthManager
import com.roox.clawlauncher.service.OpenClawService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

enum class ServerState {
    STOPPED, STARTING, RUNNING, STOPPING, ERROR, NOT_INSTALLED
}

data class ServerStatus(
    val state: ServerState = ServerState.NOT_INSTALLED,
    val message: String = "",
    val pid: Int? = null,
    val uptime: Long = 0L,
    val port: Int = 3000,
    val version: String = "unknown",
    val logs: String = "",
    val diskUsage: String = "",
    val openclawVersion: String? = null
)

class ProcessManager(
    private val context: Context,
    private val configManager: ConfigManager,
    private val googleAuth: GoogleAuthManager? = null
) {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status

    private var process: Process? = null
    private var startTime: Long = 0L
    private val logBuffer = StringBuilder()
    @Volatile private var tokenRefreshTimer: Thread? = null

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val nodeBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir

    // Use BootstrapManager's recursive search logic for finding openclaw.js
    private val bootstrapManager by lazy { BootstrapManager(context) }

    private val openclawMain: File? get() {
        val path = bootstrapManager.getOpenclawMainPath()
        return if (path != null) File(path) else null
    }

    val isInstalled: Boolean get() = nodeBin.exists() && (openclawMain != null || bootstrapManager.isOpenClawInstalled)

    init {
        checkState()
    }

    private fun checkState() {
        if (isInstalled) {
            val version = bootstrapManager.getOpenClawVersion()
            val diskUsage = formatDiskUsage(bootstrapManager.getDiskUsage())
            _status.value = ServerStatus(
                state = ServerState.STOPPED,
                message = "Ready to start",
                openclawVersion = version,
                diskUsage = diskUsage
            )
        } else {
            _status.value = ServerStatus(state = ServerState.NOT_INSTALLED, message = "Run Setup first")
        }
    }

    /**
     * Public method to force re-check installation state.
     * Call this after setup completes or when navigating back to control panel.
     */
    fun refreshState() {
        if (process?.isAlive == true) {
            // Don't change state if process is running
            val version = bootstrapManager.getOpenClawVersion()
            val diskUsage = formatDiskUsage(bootstrapManager.getDiskUsage())
            _status.value = _status.value.copy(
                openclawVersion = version,
                diskUsage = diskUsage
            )
            return
        }
        checkState()
    }

    private fun formatDiskUsage(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / 1024)} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024L * 1024 * 1024))} GB"
        }
    }

    private fun buildEnv(): Map<String, String> {
        val config = configManager.config.value
        // OPENCLAW_HOME should be baseDir itself (not baseDir/.openclaw/)
        // OpenClaw creates .openclaw/ inside HOME, so HOME=baseDir means config at baseDir/.openclaw/
        val binDir = File(baseDir, "bin").absolutePath

        val env = mutableMapOf(
            "HOME" to baseDir.absolutePath,
            "XDG_CONFIG_HOME" to baseDir.absolutePath,
            "PATH" to "$binDir:$nativeLibDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeLibDir,
            "NODE_ENV" to "production",
            "NODE_OPTIONS" to "--unhandled-rejections=warn",
            "TERM" to "xterm-256color",
            "npm_config_prefix" to baseDir.absolutePath,
            "npm_config_cache" to File(baseDir, ".npm-cache").apply { mkdirs() }.absolutePath,
            "NODE_PATH" to "${baseDir.absolutePath}/lib/node_modules:${baseDir.absolutePath}/node_modules",
            "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "TMP" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "TEMP" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "OPENCLAW_TMP_DIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "OPENCLAW_MDNS" to "false",
            "OPENCLAW_BONJOUR" to "false"
        )

        // Load .env from ALL locations
        val dotDir = File(baseDir, ".openclaw")
        val envFiles = listOf(
            File(baseDir, ".env"),
            File(dotDir, ".env")
        )
        for (envFile in envFiles) {
            if (envFile.exists()) {
                envFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            env[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }
            }
        }

        // Root access: add su paths and enable rootexec
        if (config.rootEnabled) {
            env["CLAW_ROOT_ENABLED"] = "true"
            // Ensure su is accessible
            val suPaths = "/sbin:/system/bin:/system/xbin:/data/local/xbin:/su/bin:/magisk/.core/bin"
            env["PATH"] = "${env["PATH"]}:$suPaths"
        }

        // Also set API key directly from config (most reliable)
        when (config.aiProvider) {
            "openrouter" -> if (config.aiApiKey.isNotBlank()) env["OPENROUTER_API_KEY"] = config.aiApiKey
            "google" -> if (config.aiApiKey.isNotBlank()) env["GEMINI_API_KEY"] = config.aiApiKey
            "openai" -> if (config.aiApiKey.isNotBlank()) env["OPENAI_API_KEY"] = config.aiApiKey
            "anthropic" -> if (config.aiApiKey.isNotBlank()) env["ANTHROPIC_API_KEY"] = config.aiApiKey
        }

        return env
    }

    suspend fun start() {
        if (!isInstalled) {
            _status.value = ServerStatus(state = ServerState.NOT_INSTALLED, message = "Run Setup first")
            return
        }

        val mainFile = openclawMain
        if (mainFile == null || !mainFile.exists()) {
            _status.value = ServerStatus(state = ServerState.NOT_INSTALLED, message = "OpenClaw binary not found. Run Setup.")
            return
        }

        // Save config before starting
        configManager.saveConfig()

        // Kill any existing gateway process (tracked or orphaned)
        stop()
        withContext(Dispatchers.IO) {
            try {
                // Also kill any orphaned node processes from previous sessions
                val port = configManager.config.value.port
                val killOrphan = ProcessBuilder("sh", "-c",
                    "for pid in \$(cat ${baseDir.absolutePath}/.openclaw-pid 2>/dev/null) " +
                    "\$(pgrep -f 'gateway run.*--port $port' 2>/dev/null); do " +
                    "kill \$pid 2>/dev/null; done; rm -f ${baseDir.absolutePath}/.openclaw-pid"
                )
                killOrphan.directory(baseDir)
                killOrphan.start().waitFor()
                delay(500)
            } catch (_: Exception) {}
        }

        _status.value = ServerStatus(state = ServerState.STARTING, message = "Starting OpenClaw gateway...")
        logBuffer.clear()
        appendLog("→ Starting OpenClaw gateway...")

        // Google account sign-in: make sure we hold a FRESH access token so the
        // gateway starts with valid OAuth credentials.
        if (configManager.config.value.aiProvider == ConfigManager.GOOGLE_CLI_PROVIDER && googleAuth != null) {
            appendLog("→ Checking Google sign-in token...")
            val ok = googleAuth.refreshIfNeeded()
            if (ok) appendLog("✓ Google token valid")
            else {
                val err = googleAuth.session.value.lastError
                if (!googleAuth.session.value.isSignedIn)
                    appendLog("⚠️ Not signed in to Google — sign in from Settings → AI Model")
                else if (err != null)
                    appendLog("⚠️ Token refresh: $err")
            }
        }


        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                val port = configManager.config.value.port

                // Preload android-patch.js to filter broken network interfaces
                val patchFile = File(baseDir, "android-patch.js")
                val tmpDir = File(baseDir, "tmp").apply { mkdirs() }.absolutePath
                val locksDir = File(baseDir, "locks").apply { mkdirs() }.absolutePath
                patchFile.writeText("""
const os = require('os');
const path = require('path');
const fs = require('fs');
const tmpDir = ${"\"$tmpDir\""};
const locksDir = ${"\"$locksDir\""};
try { fs.mkdirSync(tmpDir, { recursive: true }); } catch (e) {}
try { fs.mkdirSync(locksDir, { recursive: true }); } catch (e) {}
process.env.TMPDIR = tmpDir;
process.env.TMP = tmpDir;
process.env.TEMP = tmpDir;
process.env.OPENCLAW_STATE_LOCKS_DIR = locksDir;
os.tmpdir = function() { return tmpDir; };
if (typeof os.tmpDir === 'function') os.tmpDir = function() { return tmpDir; };
// ── HARD-CODED /tmp FIX ────────────────────────────────────────────────
// OpenClaw's resolveStateLifecycleRuntimeDirectory() returns the literal
// '/tmp' (hard-coded; ignores TMPDIR/os.tmpdir). /tmp can't be created by
// an Android app, so the gateway dies with:
//   EACCES: permission denied, mkdir '/tmp/openclaw-state-locks-<uid>'
// The lock sqlite files are then opened natively (node:sqlite), which a
// JS-level fs monkey-patch cannot redirect. The only reliable fix is to
// rewrite the installed source in place (idempotent, version-keyed) so the
// function honors OPENCLAW_STATE_LOCKS_DIR. Two source forms exist inside
// the dist bundles — minified (backticks) and pretty-printed (quotes) —
// so we patch with a single quote-style-agnostic regex, e.g.:
//   process.platform===`win32`?...:`/tmp`
//   process.platform === "win32" ? ... : "/tmp";
(function patchOpenClawTmpLocks() {
  try {
    // Under `node --require patch.js <openclawMain> gateway run ...`,
    // argv[1] is the main script path (argv[2] would be the CLI subcommand).
    const mainFile = process.argv[1] || '';
    const pkgRoot = path.dirname(mainFile); // .../node_modules/openclaw
    let version = 'unknown';
    try { version = JSON.parse(fs.readFileSync(path.join(pkgRoot, 'package.json'), 'utf8')).version || 'unknown'; } catch (e) {}
    // v2 scheme: covers both minified and pretty-printed bundle forms, and
    // re-patches installs that already received the earlier (broken) v1 patch.
    const flagFile = path.join(locksDir, '.tmp-locks-patched-v2-' + version);
    if (fs.existsSync(flagFile)) return; // already patched for this openclaw version

    const RET_RX = /process\.platform\s*={2,3}\s*(["'`])win32\1\s*\?\s*path\.join\(os\.homedir\(\),\s*(["'`])AppData\2\s*,\s*(["'`])Local\3\s*,\s*(["'`])OpenClaw\4\s*,\s*(["'`])locks\5\s*\)\s*:\s*["'`]\/tmp["'`]/g;

    let patched = 0, seen = 0;
    const visited = new Set();
    const scan = function(dir) {
      if (visited.has(dir)) return;
      visited.add(dir);
      let entries;
      try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
      for (let i = 0; i < entries.length; i++) {
        const ent = entries[i];
        const full = path.join(dir, ent.name);
        if (ent.isDirectory()) {
          if (ent.name === 'node_modules') continue;
          scan(full);
        } else if (/\.(mjs|cjs|js)$/.test(ent.name)) {
          let src;
          try { src = fs.readFileSync(full, 'utf8'); } catch (e) { continue; }
          if (src.indexOf('resolveStateLifecycleRuntimeDirectory') === -1) continue;
          seen++;
          let next = src.replace(RET_RX, function(m) {
            return '(process.env.OPENCLAW_STATE_LOCKS_DIR&&process.platform!=="win32")?process.env.OPENCLAW_STATE_LOCKS_DIR:(' + m + ')';
          });
          if (next !== src) {
            try {
              fs.writeFileSync(full, next);
              patched++;
              console.error('[claw-patch] patched /tmp state-locks in: ' + full);
            } catch (e) {
              console.error('[claw-patch] cannot write ' + full + ': ' + e.message);
            }
          }
        }
      }
    };
    scan(path.join(pkgRoot, 'dist'));
    scan(path.join(pkgRoot, 'lib'));

    if (patched > 0) {
      try { fs.writeFileSync(flagFile, 'patched=' + patched + ' seen=' + seen + '\n'); } catch (e) {}
    } else {
      // No live declaration found (maybe already re-export-only bundles, or a
      // new rewrite). Still drop a flag so we don't re-scan every boot, but
      // keep a warning visible in the log.
      console.error('[claw-patch] WARN: no /tmp-return declaration patched (seen files=' + seen + ', version ' + version + ')');
      try { fs.writeFileSync(flagFile, 'patched=0 seen=' + seen + '\n'); } catch (e) {}
    }
  } catch (e) {
    console.error('[claw-patch] tmp-locks patch failed: ' + e.message);
  }
})();
// ── END /tmp FIX ───────────────────────────────────────────────────────
const origNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = function() {
    const ifaces = origNetworkInterfaces.call(this);
    const filtered = {};
    for (const [name, addrs] of Object.entries(ifaces)) {
        if (name.startsWith('rmnet') || name.startsWith('dummy') || name.startsWith('v4-')) continue;
        const valid = addrs.filter(a => !a.internal && a.address);
        if (valid.length > 0 || name === 'lo') filtered[name] = addrs;
    }
    return filtered;
};
process.on('unhandledRejection', (reason, promise) => {
    if (reason && reason.message && reason.message.includes('valid address')) return;
    console.error('Unhandled rejection:', reason);
});
""".trimIndent())
                val cmd = mutableListOf(
                    nodeBin.absolutePath,
                    "--require", patchFile.absolutePath,
                    mainFile.absolutePath,
                    "gateway", "run",
                    "--port", port.toString(),
                    "--dev",
                    "--allow-unconfigured",
                    "--bind", "loopback",
                    "--auth", "none"
                )

                appendLog("→ Command: ${cmd.joinToString(" ")}")
                appendLog("→ Port: $port")
                appendLog("→ Home: ${baseDir.absolutePath}")
                appendLog("→ OpenClaw: ${mainFile.absolutePath}")

                val pb = ProcessBuilder(cmd)
                pb.directory(baseDir)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc
                startTime = System.currentTimeMillis()

                // Save PID for orphan detection
                try {
                    val pidField = proc.javaClass.getDeclaredField("pid")
                    pidField.isAccessible = true
                    val pid = pidField.getInt(proc)
                    if (pid > 0) File(baseDir, ".openclaw-pid").writeText(pid.toString())
                } catch (_: Exception) {}

                // Start reading output in background
                val reader = BufferedReader(InputStreamReader(proc.inputStream))

                // Start foreground service (wrapped in try-catch — non-fatal)
                try {
                    val serviceIntent = Intent(context, OpenClawService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    appendLog("⚠️ Service start failed (non-fatal): ${e.message}")
                }

                // Wait a bit and check if process is alive
                var started = false
                val version = bootstrapManager.getOpenClawVersion()
                for (i in 1..120) { // Wait up to 60 seconds (gateway boot can be slow on first-run migrations)
                    delay(500)

                    // Read available output
                    while (reader.ready()) {
                        val line = reader.readLine() ?: break
                        appendLog(line)
                        if (line.contains("listening") || line.contains("started") || line.contains("ready") || line.contains("Gateway")) {
                            started = true
                        }
                    }

                    if (!proc.isAlive) {
                        val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
                        appendLog("❌ Process exited with code: $exit")
                        // Read remaining output before throwing
                        while (reader.ready()) {
                            val line = reader.readLine() ?: break
                            appendLog(line)
                        }
                        throw Exception("Process exited (code: $exit). Check logs.")
                    }

                    if (started || i >= 120) {
                        started = true
                        break
                    }
                }

                if (started && proc.isAlive) {
                    appendLog("✓ OpenClaw is running!")
                    // Save running state for auto-restart after boot
                    context.getSharedPreferences("claw_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("was_running", true).apply()
                    _status.value = ServerStatus(
                        state = ServerState.RUNNING,
                        message = "OpenClaw is Live",
                        port = port,
                        uptime = 0,
                        logs = logBuffer.toString(),
                        openclawVersion = version,
                        diskUsage = formatDiskUsage(bootstrapManager.getDiskUsage())
                    )

                    // Continue reading logs in background
                    Thread {
                        try {
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                appendLog(line!!)
                                _status.value = _status.value.copy(
                                    logs = logBuffer.toString(),
                                    uptime = (System.currentTimeMillis() - startTime) / 1000
                                )
                            }
                        } catch (_: Exception) { }
                        // Process ended
                        stopTokenRefreshTimer()
                        _status.value = _status.value.copy(
                            state = ServerState.STOPPED,
                            message = "OpenClaw stopped"
                        )
                    }.start()

                    // Keep the Google OAuth token fresh while the server runs
                    startTokenRefreshTimer(proc)
                } else {
                    throw Exception("Failed to start within timeout")
                }

            } catch (e: Exception) {
                appendLog("❌ Error: ${e.message}")
                _status.value = ServerStatus(
                    state = ServerState.ERROR,
                    message = e.message ?: "Unknown error",
                    logs = logBuffer.toString()
                )
                // Stop service
                context.stopService(Intent(context, OpenClawService::class.java))
            }
        }
    }

    /**
     * While the gateway runs, refresh the Google OAuth token every 20 minutes so
     * the on-disk credential files and the auth-profile store stay valid. The
     * timer stops itself when the process exits.
     */
    private fun startTokenRefreshTimer(proc: Process) {
        stopTokenRefreshTimer()
        val auth = googleAuth ?: return
        tokenRefreshTimer = Thread {
            while (true) {
                try { Thread.sleep(20 * 60 * 1000L) } catch (_: InterruptedException) { break }
                if (!proc.isAlive) break
                try {
                    if (configManager.config.value.aiProvider == ConfigManager.GOOGLE_CLI_PROVIDER) {
                        val ok = runBlocking { auth.refreshIfNeeded() }
                        appendLog(if (ok) "✓ Google token refreshed" else "⚠️ Google token refresh failed: ${auth.session.value.lastError ?: ""}")
                    }
                } catch (_: Exception) { }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopTokenRefreshTimer() {
        tokenRefreshTimer?.interrupt()
        tokenRefreshTimer = null
    }

    suspend fun stop() {
        _status.value = _status.value.copy(state = ServerState.STOPPING, message = "Stopping...")
        appendLog("→ Stopping OpenClaw...")
        stopTokenRefreshTimer()
        context.getSharedPreferences("claw_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("was_running", false).apply()

        withContext(Dispatchers.IO) {
            try {
                process?.destroy()
                delay(2000)
                if (process?.isAlive == true) {
                    process?.destroyForcibly()
                }
                process = null
                appendLog("✓ Stopped")
                _status.value = ServerStatus(
                    state = ServerState.STOPPED,
                    message = "Stopped",
                    logs = logBuffer.toString(),
                    openclawVersion = bootstrapManager.getOpenClawVersion(),
                    diskUsage = formatDiskUsage(bootstrapManager.getDiskUsage())
                )
                // Stop foreground service
                context.stopService(Intent(context, OpenClawService::class.java))
            } catch (e: Exception) {
                appendLog("❌ Stop error: ${e.message}")
                _status.value = ServerStatus(
                    state = ServerState.ERROR,
                    message = "Stop error: ${e.message}",
                    logs = logBuffer.toString()
                )
            }
        }
    }

    suspend fun restart() {
        stop()
        delay(1000)
        start()
    }

    fun getStatusInfo(): Map<String, String> {
        val s = _status.value
        val uptimeStr = if (s.state == ServerState.RUNNING) {
            val secs = (System.currentTimeMillis() - startTime) / 1000
            "${secs / 3600}h ${(secs % 3600) / 60}m ${secs % 60}s"
        } else "N/A"

        return mapOf(
            "State" to s.state.name,
            "Uptime" to uptimeStr,
            "Port" to s.port.toString(),
            "Node.js" to if (nodeBin.exists()) "OK (Termux)" else "Not found",
            "Node Path" to nodeBin.absolutePath,
            "Libs" to nativeLibDir,
            "OpenClaw" to if (openclawMain?.exists() == true) "✓ Installed" else "Not installed",
            "OpenClaw Path" to (openclawMain?.absolutePath ?: "N/A"),
            "Version" to (s.openclawVersion ?: "unknown"),
            "Disk Usage" to s.diskUsage,
            "Base Dir" to baseDir.absolutePath,
            "Workspace" to File(baseDir, "workspace").absolutePath,
            "Architecture" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        )
    }

    fun getLogs(): String = logBuffer.toString()

    private fun appendLog(msg: String) {
        logBuffer.appendLine(msg)
        // Keep last 200 lines
        val lines = logBuffer.lines()
        if (lines.size > 200) {
            logBuffer.clear()
            logBuffer.append(lines.takeLast(200).joinToString("\n"))
        }
    }
}
