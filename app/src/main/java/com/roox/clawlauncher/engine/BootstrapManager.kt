package com.roox.clawlauncher.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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

    // Use tar.gz (not tar.xz) since we extract via Java GZIPInputStream
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
                baseDir.mkdirs()
                nodeDir.mkdirs()
                File(baseDir, "workspace").mkdirs()

                // Step 1: Download Node.js
                if (!isNodeInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Downloading Node.js $nodeVersion ($arch)...",
                        progress = 0.05f
                    )
                    log("→ Architecture: $arch")
                    log("→ Downloading Node.js $nodeVersion (tar.gz)...")

                    // Use .tar.gz format (Java can decompress gzip natively)
                    val nodeUrl = "https://nodejs.org/dist/$nodeVersion/node-$nodeVersion-linux-$arch.tar.gz"
                    val nodeTar = File(baseDir, "node-download.tar.gz")

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

                    // Step 2: Extract using Java (no system tar needed!)
                    _progress.value = _progress.value.copy(
                        step = "Extracting Node.js...",
                        progress = 0.42f
                    )
                    log("→ Extracting Node.js (Java-based)...")

                    extractTarGz(nodeTar, nodeDir, stripComponents = 1) { extracted ->
                        if (extracted % 100 == 0) {
                            _progress.value = _progress.value.copy(
                                step = "Extracting... ($extracted files)",
                                progress = 0.42f + (minOf(extracted, 500).toFloat() / 500f * 0.13f)
                            )
                        }
                    }

                    // Make binaries executable
                    val binDir = File(nodeDir, "bin")
                    binDir.listFiles()?.forEach { it.setExecutable(true, false) }
                    nodeBin.setExecutable(true, false)

                    nodeTar.delete()
                    log("✓ Node.js extracted")

                    // Verify node works
                    val nodeVer = runCommandOutput(nodeBin.absolutePath, "--version")
                    log("✓ Node.js version: $nodeVer")

                    if (nodeVer.isBlank() || !nodeVer.startsWith("v")) {
                        throw Exception("Node.js verification failed. Output: $nodeVer")
                    }
                } else {
                    log("✓ Node.js already installed")
                    _progress.value = _progress.value.copy(progress = 0.55f)
                }

                // Step 3: Install OpenClaw via npm
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw (this takes a few minutes)...",
                        progress = 0.58f
                    )
                    log("→ Installing openclaw via npm...")

                    val env = buildEnv()
                    val installExit = runCommandWithEnv(
                        env, baseDir,
                        nodeBin.absolutePath, npmBin.absolutePath,
                        "install", "-g", "openclaw", "--no-optional", "--no-audit", "--no-fund"
                    ) { line ->
                        log("  $line")
                        if (line.contains("added")) {
                            _progress.value = _progress.value.copy(
                                step = "Installing OpenClaw... (finalizing)",
                                progress = 0.88f
                            )
                        }
                    }

                    if (installExit != 0) {
                        throw Exception("npm install openclaw failed (exit: $installExit)")
                    }

                    // Make openclaw bin executable
                    if (openclawBin.exists()) {
                        openclawBin.setExecutable(true, false)
                    }

                    log("✓ OpenClaw installed")
                } else {
                    log("✓ OpenClaw already installed")
                }

                // Step 4: Initialize workspace
                _progress.value = _progress.value.copy(
                    step = "Setting up workspace...",
                    progress = 0.92f
                )
                log("→ Initializing workspace...")

                val workspaceDir = File(baseDir, "workspace")
                workspaceDir.mkdirs()

                createFileIfMissing(File(workspaceDir, "AGENTS.md"), "# AGENTS.md\n\nYour OpenClaw workspace on Android.\n")
                createFileIfMissing(File(workspaceDir, "SOUL.md"), "# SOUL.md\n\nYou are a helpful AI assistant running on Android via ClawLauncher.\n")

                log("✓ Workspace ready")

                // Done!
                _progress.value = SetupProgress(
                    isRunning = false,
                    step = "Setup complete! 🦀",
                    progress = 1f,
                    isComplete = true,
                    log = _progress.value.log + "✅ All done! Go back and start OpenClaw.\n"
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
                    env, baseDir,
                    nodeBin.absolutePath, npmBin.absolutePath,
                    "update", "-g", "openclaw"
                ) { line -> log("  $line") }

                if (exitCode != 0) throw Exception("Update failed (exit: $exitCode)")
                log("✓ Update complete")

                _progress.value = SetupProgress(
                    isRunning = false, step = "Updated!", progress = 1f,
                    isComplete = true, log = _progress.value.log
                )
            } catch (e: Exception) {
                log("❌ ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    // =========================================
    // Pure Java tar.gz extraction (no system commands!)
    // =========================================
    private fun extractTarGz(tarGzFile: File, destDir: File, stripComponents: Int = 0, onFile: (Int) -> Unit = {}) {
        var count = 0
        FileInputStream(tarGzFile).use { fis ->
            GZIPInputStream(fis).use { gzis ->
                TarArchiveInputStream(gzis).use { tarIn ->
                    var entry: TarArchiveEntry?
                    while (tarIn.nextEntry.also { entry = it } != null) {
                        val e = entry ?: continue
                        // Strip leading path components
                        val parts = e.name.split("/").drop(stripComponents)
                        if (parts.isEmpty()) continue
                        val relativePath = parts.joinToString("/")
                        if (relativePath.isBlank()) continue

                        val outFile = File(destDir, relativePath)

                        // Security: prevent path traversal
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) continue

                        if (e.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                tarIn.copyTo(fos)
                            }
                            // Preserve executable permission
                            if (e.mode and 0b001001001 != 0) { // any execute bit
                                outFile.setExecutable(true, false)
                            }
                        }
                        count++
                        onFile(count)
                    }
                }
            }
        }
        log("✓ Extracted $count files")
    }

    private fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to baseDir.absolutePath,
        "PATH" to "${nodeDir.absolutePath}/bin:/system/bin:/system/xbin",
        "NODE_ENV" to "production",
        "TERM" to "xterm-256color",
        "npm_config_prefix" to nodeDir.absolutePath,
        "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath
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

    private fun runCommandOutput(vararg cmd: String): String {
        return try {
            val pb = ProcessBuilder(*cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(30, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun runCommandWithEnv(env: Map<String, String>, workDir: File, vararg cmd: String, onLine: (String) -> Unit = {}): Int {
        val pb = ProcessBuilder(*cmd)
        pb.directory(workDir)
        pb.environment().clear()
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

    private fun createFileIfMissing(file: File, content: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }
}
