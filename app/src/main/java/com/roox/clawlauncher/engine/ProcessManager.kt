package com.roox.clawlauncher.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
    val logs: String = ""
)

class ProcessManager(private val context: Context, private val configManager: ConfigManager) {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status

    private var process: Process? = null
    private var startTime: Long = 0L
    private val logBuffer = StringBuilder()

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val nodeBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir
    private val openclawMain: File get() = File(baseDir, "node_modules/openclaw/bin/openclaw.js")

    val isInstalled: Boolean get() = nodeBin.exists() && openclawMain.exists()

    init {
        checkState()
    }

    private fun checkState() {
        _status.value = if (isInstalled) {
            ServerStatus(state = ServerState.STOPPED, message = "Ready to start")
        } else {
            ServerStatus(state = ServerState.NOT_INSTALLED, message = "Run Setup first")
        }
    }

    private fun buildEnv(): Map<String, String> {
        val config = configManager.config.value
        val env = mutableMapOf(
            "HOME" to baseDir.absolutePath,
            "PATH" to "$nativeLibDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeLibDir,
            "NODE_ENV" to "production",
            "TERM" to "xterm-256color",
            "npm_config_prefix" to baseDir.absolutePath,
            "OPENCLAW_WORKSPACE" to File(baseDir, "workspace").absolutePath
        )

        // Load .env file if exists
        val envFile = File(baseDir, ".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val (key, value) = trimmed.split("=", limit = 2)
                    env[key.trim()] = value.trim()
                }
            }
        }

        // Ensure TMPDIR exists
        File(baseDir, "tmp").mkdirs()
        env["TMPDIR"] = File(baseDir, "tmp").absolutePath

        return env
    }

    suspend fun start() {
        if (!isInstalled) {
            _status.value = ServerStatus(state = ServerState.NOT_INSTALLED, message = "Run Setup first")
            return
        }

        // Save config before starting
        configManager.saveConfig()

        _status.value = ServerStatus(state = ServerState.STARTING, message = "Starting OpenClaw gateway...")
        logBuffer.clear()
        appendLog("→ Starting OpenClaw gateway...")

        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                val port = configManager.config.value.port

                val cmd = listOf(
                    nodeBin.absolutePath,
                    openclawMain.absolutePath,
                    "gateway", "start", "--foreground"
                )

                appendLog("→ Command: ${cmd.joinToString(" ")}")
                appendLog("→ Port: $port")
                appendLog("→ Home: ${baseDir.absolutePath}")

                val pb = ProcessBuilder(cmd)
                pb.directory(baseDir)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                process = pb.start()
                startTime = System.currentTimeMillis()

                // Start reading output in background
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))

                // Start foreground service
                val serviceIntent = Intent(context, OpenClawService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Wait a bit and check if process is alive
                var started = false
                for (i in 1..30) { // Wait up to 15 seconds
                    delay(500)

                    // Read available output
                    while (reader.ready()) {
                        val line = reader.readLine() ?: break
                        appendLog(line)
                        if (line.contains("listening") || line.contains("started") || line.contains("ready") || line.contains("Gateway")) {
                            started = true
                        }
                    }

                    if (process?.isAlive != true) {
                        val exit = process?.exitValue() ?: -1
                        throw Exception("Process exited immediately (code: $exit)")
                    }

                    if (started || i >= 10) {
                        started = true
                        break
                    }
                }

                if (started && process?.isAlive == true) {
                    appendLog("✓ OpenClaw is running!")
                    _status.value = ServerStatus(
                        state = ServerState.RUNNING,
                        message = "OpenClaw is Live",
                        port = port,
                        uptime = 0,
                        logs = logBuffer.toString()
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
                        _status.value = _status.value.copy(
                            state = ServerState.STOPPED,
                            message = "OpenClaw stopped"
                        )
                    }.start()
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

    suspend fun stop() {
        _status.value = _status.value.copy(state = ServerState.STOPPING, message = "Stopping...")
        appendLog("→ Stopping OpenClaw...")

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
                    logs = logBuffer.toString()
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
            "OpenClaw" to if (openclawMain.exists()) "✓ Installed" else "Not installed",
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
