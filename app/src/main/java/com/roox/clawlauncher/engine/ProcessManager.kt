package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class ServerState {
    STOPPED, STARTING, RUNNING, STOPPING, ERROR, NOT_INSTALLED
}

data class ServerStatus(
    val state: ServerState = ServerState.NOT_INSTALLED,
    val message: String = "",
    val pid: Int? = null,
    val uptime: Long = 0L,
    val port: Int = 3000,
    val version: String = "unknown"
)

class ProcessManager(private val context: Context) {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status

    private var process: Process? = null
    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val nodeDir: File get() = File(baseDir, "node")
    private val nodeBin: File get() = File(nodeDir, "bin/node")

    val isInstalled: Boolean get() = nodeBin.exists()

    init {
        checkState()
    }

    private fun checkState() {
        _status.value = if (isInstalled) {
            ServerStatus(state = ServerState.STOPPED, message = "OpenClaw is ready")
        } else {
            ServerStatus(state = ServerState.NOT_INSTALLED, message = "Setup required")
        }
    }

    suspend fun start() {
        if (!isInstalled) {
            _status.value = ServerStatus(state = ServerState.NOT_INSTALLED, message = "Please run Setup first")
            return
        }

        _status.value = ServerStatus(state = ServerState.STARTING, message = "Initializing OpenClaw...")

        withContext(Dispatchers.IO) {
            try {
                val env = arrayOf(
                    "HOME=${baseDir.absolutePath}",
                    "PATH=${nodeDir.absolutePath}/bin:${System.getenv("PATH")}",
                    "NODE_ENV=production",
                    "TERM=xterm-256color"
                )

                val pb = ProcessBuilder(
                    nodeBin.absolutePath,
                    File(baseDir, "node_modules/.bin/openclaw").absolutePath,
                    "gateway", "start"
                )
                pb.directory(baseDir)
                pb.environment().putAll(env.map {
                    val parts = it.split("=", limit = 2)
                    parts[0] to parts[1]
                }.toMap())
                pb.redirectErrorStream(true)

                process = pb.start()

                // Wait a bit and check if it's still running
                Thread.sleep(3000)

                if (process?.isAlive == true) {
                    _status.value = ServerStatus(
                        state = ServerState.RUNNING,
                        message = "OpenClaw is Live",
                        pid = null,
                        port = 3000
                    )
                } else {
                    val exitCode = process?.exitValue() ?: -1
                    _status.value = ServerStatus(
                        state = ServerState.ERROR,
                        message = "Failed to start (exit code: $exitCode)"
                    )
                }
            } catch (e: Exception) {
                _status.value = ServerStatus(
                    state = ServerState.ERROR,
                    message = "Error: ${e.message}"
                )
            }
        }
    }

    suspend fun stop() {
        _status.value = _status.value.copy(state = ServerState.STOPPING, message = "Stopping OpenClaw...")

        withContext(Dispatchers.IO) {
            try {
                process?.destroy()
                process?.waitFor()
                process = null
                _status.value = ServerStatus(state = ServerState.STOPPED, message = "OpenClaw stopped")
            } catch (e: Exception) {
                _status.value = ServerStatus(state = ServerState.ERROR, message = "Stop error: ${e.message}")
            }
        }
    }

    suspend fun restart() {
        stop()
        start()
    }

    fun getStatusInfo(): Map<String, String> {
        return mapOf(
            "State" to _status.value.state.name,
            "PID" to (_status.value.pid?.toString() ?: "N/A"),
            "Port" to _status.value.port.toString(),
            "Base Dir" to baseDir.absolutePath,
            "Node Installed" to isInstalled.toString(),
            "Node Path" to nodeBin.absolutePath,
        )
    }
}
