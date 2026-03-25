package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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

/**
 * Bootstrap manager that extracts Termux-compiled Node.js from APK assets.
 * 
 * Termux Node.js is compiled against Android's Bionic libc (not glibc),
 * so it runs natively on Android without root or special permissions.
 * 
 * Asset layout (bundled during CI):
 *   assets/node-android/bin/node       — Termux node binary (aarch64)
 *   assets/node-android/lib/*.so       — shared libraries (libc++, openssl, etc)
 *   assets/node-android/lib/node_modules/npm/ — npm package manager
 */
class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    val nodeBin: File get() = File(baseDir, "android-node/bin/node")
    private val nodeLibDir: File get() = File(baseDir, "android-node/lib")
    private val npmCli: File get() = File(baseDir, "android-node/lib/node_modules/npm/bin/npm-cli.js")
    private val nodeModulesDir: File get() = File(baseDir, "node_modules")
    private val openclawMain: File get() = File(nodeModulesDir, "openclaw/bin/openclaw.js")

    val isNodeInstalled: Boolean get() = nodeBin.exists() && nodeBin.canExecute()
    val isNpmInstalled: Boolean get() = npmCli.exists()
    val isOpenClawInstalled: Boolean get() = openclawMain.exists()

    private fun log(msg: String) {
        _progress.value = _progress.value.copy(log = _progress.value.log + msg + "\n")
    }

    suspend fun runSetup() {
        _progress.value = SetupProgress(isRunning = true, step = "Preparing environment...", progress = 0.02f)

        withContext(Dispatchers.IO) {
            try {
                baseDir.mkdirs()
                File(baseDir, "workspace").mkdirs()
                File(baseDir, "tmp").mkdirs()

                // Step 1: Extract Node.js from APK assets
                if (!isNodeInstalled) {
                    _progress.value = _progress.value.copy(step = "Extracting Node.js for Android...", progress = 0.05f)
                    log("→ Extracting Termux Node.js from assets...")

                    val destDir = File(baseDir, "android-node")
                    destDir.mkdirs()

                    extractAssetsRecursive("node-android", destDir) { count ->
                        if (count % 50 == 0) {
                            _progress.value = _progress.value.copy(
                                step = "Extracting... ($count files)",
                                progress = 0.05f + minOf(count, 500).toFloat() / 500f * 0.25f
                            )
                        }
                    }

                    // Make node executable
                    nodeBin.setExecutable(true, false)

                    // Make all files in bin/ executable
                    File(destDir, "bin").listFiles()?.forEach { it.setExecutable(true, false) }

                    log("✓ Node.js extracted to ${destDir.absolutePath}")
                    log("→ Node binary: ${nodeBin.absolutePath}")
                    log("→ Exists: ${nodeBin.exists()}, Executable: ${nodeBin.canExecute()}")
                    log("→ Size: ${nodeBin.length() / 1024}KB")
                } else {
                    log("✓ Node.js already installed")
                }

                _progress.value = _progress.value.copy(progress = 0.35f)

                // Step 2: Verify Node.js works
                _progress.value = _progress.value.copy(step = "Verifying Node.js...", progress = 0.38f)
                val env = buildEnv()
                val nodeVer = runCommandWithEnvOutput(env, nodeBin.absolutePath, "--version")
                log("→ Node.js version output: $nodeVer")

                if (!nodeVer.startsWith("v")) {
                    // Try to get more info about why it failed
                    val fileInfo = runCommandOutput("file", nodeBin.absolutePath)
                    log("→ File info: $fileInfo")
                    val lsInfo = runCommandOutput("ls", "-la", nodeBin.absolutePath)
                    log("→ ls: $lsInfo")
                    throw Exception("Node.js failed to execute. Output: $nodeVer")
                }

                log("✓ Node.js $nodeVer working!")

                // Step 3: Verify npm
                _progress.value = _progress.value.copy(step = "Verifying npm...", progress = 0.42f)
                if (isNpmInstalled) {
                    val npmVer = runCommandWithEnvOutput(env, nodeBin.absolutePath, npmCli.absolutePath, "--version")
                    log("✓ npm $npmVer")
                } else {
                    log("⚠ npm not found — will try to install openclaw directly")
                }

                // Step 4: Install OpenClaw
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw (few minutes)...",
                        progress = 0.50f
                    )
                    log("→ Installing openclaw via npm...")

                    val installExit = runCommandWithEnv(
                        env, baseDir,
                        nodeBin.absolutePath, npmCli.absolutePath,
                        "install", "openclaw",
                        "--prefix", baseDir.absolutePath,
                        "--no-optional", "--no-audit", "--no-fund"
                    ) { line ->
                        log("  $line")
                        if (line.contains("added")) {
                            _progress.value = _progress.value.copy(
                                step = "Finalizing...",
                                progress = 0.88f
                            )
                        }
                    }

                    if (installExit != 0) {
                        throw Exception("npm install openclaw failed (exit: $installExit)")
                    }
                    log("✓ OpenClaw installed")
                } else {
                    log("✓ OpenClaw already installed")
                }

                // Step 5: Workspace
                _progress.value = _progress.value.copy(step = "Setting up workspace...", progress = 0.93f)
                val workspace = File(baseDir, "workspace")
                workspace.mkdirs()
                createIfMissing(File(workspace, "AGENTS.md"), "# AGENTS.md\n\nYour OpenClaw workspace.\n")
                createIfMissing(File(workspace, "SOUL.md"), "# SOUL.md\n\nAI assistant on Android.\n")
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
        _progress.value = SetupProgress(isRunning = true, step = "Updating...", progress = 0.1f)
        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                log("→ Updating openclaw...")
                val exit = runCommandWithEnv(
                    env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "update", "openclaw", "--prefix", baseDir.absolutePath
                ) { log("  $it") }
                if (exit != 0) throw Exception("Update failed (exit: $exit)")
                log("✓ Done")
                _progress.value = SetupProgress(isRunning = false, step = "Updated!", progress = 1f, isComplete = true, log = _progress.value.log)
            } catch (e: Exception) {
                log("❌ ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    /**
     * Recursively extract assets to a destination directory.
     */
    private fun extractAssetsRecursive(assetPath: String, destDir: File, onFile: (Int) -> Unit = {}) {
        var count = 0

        fun extract(aPath: String, dDir: File) {
            val assets = context.assets
            val list = assets.list(aPath) ?: return

            if (list.isEmpty()) {
                // It's a file
                dDir.parentFile?.mkdirs()
                assets.open(aPath).use { input ->
                    FileOutputStream(dDir).use { output ->
                        input.copyTo(output)
                    }
                }
                count++
                onFile(count)
            } else {
                // Directory
                dDir.mkdirs()
                for (item in list) {
                    extract("$aPath/$item", File(dDir, item))
                }
            }
        }

        extract(assetPath, destDir)
        log("✓ Extracted $count files from assets")
    }

    /**
     * Build environment variables for running node.
     * Sets LD_LIBRARY_PATH to our bundled Termux libs.
     */
    private fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to baseDir.absolutePath,
        "PATH" to "${File(baseDir, "android-node/bin").absolutePath}:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to nodeLibDir.absolutePath,
        "NODE_ENV" to "production",
        "TERM" to "xterm-256color",
        "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
        "npm_config_cache" to File(baseDir, ".npm-cache").apply { mkdirs() }.absolutePath,
        "npm_config_prefix" to baseDir.absolutePath,
        "npm_config_globalconfig" to File(baseDir, "npmrc").absolutePath,
        "npm_config_userconfig" to File(baseDir, "npmrc").absolutePath
    )

    private fun runCommandOutput(vararg cmd: String): String {
        return try {
            val pb = ProcessBuilder(*cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(30, TimeUnit.SECONDS)
            out
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun runCommandWithEnvOutput(env: Map<String, String>, vararg cmd: String): String {
        return try {
            val pb = ProcessBuilder(*cmd)
            pb.directory(baseDir)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(30, TimeUnit.SECONDS)
            out
        } catch (e: Exception) { "Error: ${e.message}" }
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
        while (reader.readLine().also { line = it } != null) { onLine(line!!) }
        return proc.waitFor()
    }

    private fun createIfMissing(file: File, content: String) {
        if (!file.exists()) { file.parentFile?.mkdirs(); file.writeText(content) }
    }
}
