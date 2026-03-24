package com.roox.clawlauncher.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class SetupProgress(
    val isRunning: Boolean = false,
    val step: String = "",
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val error: String? = null,
    val log: String = ""
)

class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val nodeDir: File get() = File(baseDir, "node")
    private val nodeBin: File get() = File(nodeDir, "bin/node")
    private val npmBin: File get() = File(nodeDir, "bin/npm")
    private val npxBin: File get() = File(nodeDir, "bin/npx")
    private val openclawBin: File get() = File(nodeDir, "bin/openclaw")

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Node.js LTS for Android
    private val nodeVersion = "v20.18.1"

    val isNodeInstalled: Boolean get() = nodeBin.exists() && nodeBin.canExecute()
    val isOpenClawInstalled: Boolean get() = openclawBin.exists() || File(nodeDir, "lib/node_modules/openclaw").exists()

    private val arch: String get() = when {
        Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "arm64"
        Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x64"
        Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") } -> "armv7l"
        else -> "x64"
    }

    private fun log(msg: String) {
        _progress.value = _progress.value.copy(log = _progress.value.log + msg + "\n")
    }

    suspend fun runSetup() {
        _progress.value = SetupProgress(isRunning = true, step = "Preparing environment...", progress = 0.02f)

        withContext(Dispatchers.IO) {
            try {
                // Create base dirs
                baseDir.mkdirs()
                nodeDir.mkdirs()
                File(baseDir, "workspace").mkdirs()

                // Step 1: Download Node.js if not installed
                if (!isNodeInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Downloading Node.js $nodeVersion ($arch)...",
                        progress = 0.05f
                    )
                    log("→ Architecture: $arch")
                    log("→ Downloading Node.js $nodeVersion...")

                    val nodeUrl = "https://nodejs.org/dist/$nodeVersion/node-$nodeVersion-linux-$arch.tar.xz"
                    val nodeTar = File(baseDir, "node-download.tar.xz")

                    downloadFile(nodeUrl, nodeTar) { downloaded, total ->
                        val mb = downloaded / (1024.0 * 1024.0)
                        val totalMb = if (total > 0) total / (1024.0 * 1024.0) else 0.0
                        val pct = if (total > 0) downloaded.toFloat() / total else 0f
                        _progress.value = _progress.value.copy(
                            step = "Downloading Node.js... ${"%.1f".format(mb)}/${"%.0f".format(totalMb)} MB",
                            progress = 0.05f + (pct * 0.35f)
                        )
                    }
                    log("✓ Download complete: ${nodeTar.length() / (1024 * 1024)} MB")

                    // Step 2: Extract
                    _progress.value = _progress.value.copy(
                        step = "Extracting Node.js...",
                        progress = 0.42f
                    )
                    log("→ Extracting Node.js...")

                    val exitCode = runCommand(
                        "tar", "xf", nodeTar.absolutePath,
                        "--strip-components=1",
                        "-C", nodeDir.absolutePath
                    )

                    if (exitCode != 0) {
                        throw Exception("Failed to extract Node.js (exit: $exitCode)")
                    }

                    // Make binaries executable
                    nodeBin.setExecutable(true)
                    npmBin.setExecutable(true)
                    npxBin.setExecutable(true)

                    nodeTar.delete()
                    log("✓ Node.js extracted")

                    // Verify
                    val nodeVer = runCommandOutput(nodeBin.absolutePath, "--version")
                    log("✓ Node.js version: $nodeVer")
                } else {
                    log("✓ Node.js already installed")
                    _progress.value = _progress.value.copy(progress = 0.42f)
                }

                // Step 3: Install OpenClaw via npm
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw (this may take a few minutes)...",
                        progress = 0.50f
                    )
                    log("→ Installing openclaw via npm...")

                    val env = buildEnv()
                    val installExit = runCommandWithEnv(
                        env,
                        nodeBin.absolutePath, npmBin.absolutePath,
                        "install", "-g", "openclaw", "--no-optional"
                    ) { line ->
                        log("  npm: $line")
                        // Update progress based on npm output
                        if (line.contains("added")) {
                            _progress.value = _progress.value.copy(progress = 0.85f)
                        }
                    }

                    if (installExit != 0) {
                        throw Exception("npm install openclaw failed (exit: $installExit)")
                    }

                    log("✓ OpenClaw installed")

                    // Verify
                    val clawVer = runCommandOutputWithEnv(env, nodeBin.absolutePath, openclawBin.absolutePath, "--version")
                    log("✓ OpenClaw version: $clawVer")
                } else {
                    log("✓ OpenClaw already installed")
                }

                // Step 4: Initialize workspace
                _progress.value = _progress.value.copy(
                    step = "Setting up workspace...",
                    progress = 0.90f
                )
                log("→ Initializing workspace...")

                val workspaceDir = File(baseDir, "workspace")
                workspaceDir.mkdirs()

                // Create essential workspace files
                createFileIfMissing(File(workspaceDir, "AGENTS.md"), """
                    |# AGENTS.md
                    |
                    |This is your OpenClaw workspace on Android.
                """.trimMargin())

                createFileIfMissing(File(workspaceDir, "SOUL.md"), """
                    |# SOUL.md
                    |
                    |You are a helpful AI assistant running on an Android device via ClawLauncher.
                    |Be concise, helpful, and friendly.
                """.trimMargin())

                log("✓ Workspace ready")

                // Done!
                _progress.value = SetupProgress(
                    isRunning = false,
                    step = "Setup complete! 🦀",
                    progress = 1f,
                    isComplete = true,
                    log = _progress.value.log + "✅ All done! Ready to start OpenClaw.\n"
                )

            } catch (e: Exception) {
                log("❌ Error: ${e.message}")
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    step = "Setup failed",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    suspend fun updateOpenClaw() {
        _progress.value = SetupProgress(isRunning = true, step = "Updating OpenClaw...", progress = 0.1f)
        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                log("→ Updating openclaw to latest...")
                val exitCode = runCommandWithEnv(
                    env,
                    nodeBin.absolutePath, npmBin.absolutePath,
                    "update", "-g", "openclaw"
                ) { line -> log("  npm: $line") }

                if (exitCode != 0) throw Exception("Update failed (exit: $exitCode)")

                val ver = runCommandOutputWithEnv(env, nodeBin.absolutePath, openclawBin.absolutePath, "--version")
                log("✓ Updated to: $ver")

                _progress.value = SetupProgress(
                    isRunning = false, step = "Updated to $ver", progress = 1f,
                    isComplete = true, log = _progress.value.log
                )
            } catch (e: Exception) {
                log("❌ ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    private fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to baseDir.absolutePath,
        "PATH" to "${nodeDir.absolutePath}/bin:/usr/local/bin:/usr/bin:/bin",
        "NODE_ENV" to "production",
        "TERM" to "xterm-256color",
        "npm_config_prefix" to nodeDir.absolutePath
    )

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Download failed: HTTP ${response.code}")
        val body = response.body ?: throw Exception("Empty response body")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(16384)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            }
        }
    }

    private fun runCommand(vararg cmd: String): Int {
        val pb = ProcessBuilder(*cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().readLines() // consume output
        return proc.waitFor()
    }

    private fun runCommandOutput(vararg cmd: String): String {
        val pb = ProcessBuilder(*cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return output
    }

    private fun runCommandWithEnv(env: Map<String, String>, vararg cmd: String, onLine: (String) -> Unit = {}): Int {
        val pb = ProcessBuilder(*cmd)
        pb.directory(baseDir)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val proc = pb.start()

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            onLine(line!!)
        }
        return proc.waitFor()
    }

    private fun runCommandOutputWithEnv(env: Map<String, String>, vararg cmd: String): String {
        val pb = ProcessBuilder(*cmd)
        pb.directory(baseDir)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return output
    }

    private fun createFileIfMissing(file: File, content: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }
}
