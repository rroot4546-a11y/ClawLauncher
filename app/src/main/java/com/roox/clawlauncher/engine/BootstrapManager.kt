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

// Node.js binary lives in nativeLibraryDir as libnode.so
// This is the ONLY directory Android allows executing binaries from.
// Shared libs (libc++, openssl, zlib) are also there.
// npm is extracted from APK assets to app data.
class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress

    private val baseDir: File get() = File(context.filesDir, "openclaw")

    // Node binary = libnode.so in nativeLibraryDir (executable!)
    val nodeBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    // Shared libraries directory (same as node binary)
    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir

    // npm extracted from assets
    private val npmDir: File get() = File(baseDir, "npm")
    private val npmCli: File get() = File(npmDir, "bin/npm-cli.js")

    // OpenClaw installed via npm
    private val nodeModulesDir: File get() = File(baseDir, "node_modules")
    private val openclawMain: File get() = File(nodeModulesDir, "openclaw/bin/openclaw.js")

    val isNodeInstalled: Boolean get() = nodeBin.exists()
    val isNpmInstalled: Boolean get() = npmCli.exists()
    val isOpenClawInstalled: Boolean get() = openclawMain.exists()

    private fun log(msg: String) {
        _progress.value = _progress.value.copy(log = _progress.value.log + msg + "\n")
    }

    suspend fun runSetup() {
        _progress.value = SetupProgress(isRunning = true, step = "Preparing...", progress = 0.02f)

        withContext(Dispatchers.IO) {
            try {
                baseDir.mkdirs()
                File(baseDir, "workspace").mkdirs()
                File(baseDir, "tmp").mkdirs()

                // Step 1: Verify Node.js binary in nativeLibraryDir
                _progress.value = _progress.value.copy(step = "Checking Node.js...", progress = 0.05f)
                log("-> Node binary path: ${nodeBin.absolutePath}")
                log("-> nativeLibraryDir: $nativeLibDir")
                log("-> Exists: ${nodeBin.exists()}")

                if (!nodeBin.exists()) {
                    throw Exception("Node.js (libnode.so) not found in native libs. Reinstall the APK.")
                }

                // List all native libs
                val nativeDir = File(nativeLibDir)
                log("-> Native libs: ${nativeDir.listFiles()?.joinToString { it.name } ?: "none"}")

                // Prepare library directory with correct versioned names
                log("-> Preparing Termux libraries...")
                val termuxLibDir = NativeLibHelper.prepareLibs(context)
                log("-> Termux lib dir: ${termuxLibDir.absolutePath}")
                log("-> Libs: ${termuxLibDir.listFiles()?.joinToString { it.name } ?: "none"}")

                // Test node execution
                log("-> Testing node --version...")
                val env = buildEnv()
                val nodeVer = runCmdOutput(env, nodeBin.absolutePath, "--version")
                log("-> Result: $nodeVer")

                if (!nodeVer.startsWith("v")) {
                    throw Exception("Node.js failed to start. Output: $nodeVer")
                }

                log("OK Node.js $nodeVer working!")
                _progress.value = _progress.value.copy(progress = 0.25f)

                // Step 2: Extract npm from assets
                if (!isNpmInstalled) {
                    _progress.value = _progress.value.copy(step = "Setting up npm...", progress = 0.30f)
                    log("-> Extracting npm from assets...")
                    npmDir.mkdirs()
                    extractAssetsRecursive("npm", npmDir)
                    log("OK npm extracted")
                } else {
                    log("OK npm already installed")
                }

                // Verify npm
                val npmVer = runCmdOutput(env, nodeBin.absolutePath, npmCli.absolutePath, "--version")
                log("-> npm version: $npmVer")
                _progress.value = _progress.value.copy(progress = 0.40f)

                // Step 3: Install OpenClaw
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw (few minutes)...",
                        progress = 0.45f
                    )
                    log("-> npm install openclaw...")

                    val exit = runCmdLines(
                        env, baseDir,
                        nodeBin.absolutePath, npmCli.absolutePath,
                        "install", "openclaw",
                        "--prefix", baseDir.absolutePath,
                        "--no-optional", "--no-audit", "--no-fund"
                    ) { line ->
                        log("  $line")
                        if (line.contains("added")) {
                            _progress.value = _progress.value.copy(step = "Finalizing...", progress = 0.85f)
                        }
                    }

                    if (exit != 0) throw Exception("npm install openclaw failed (exit: $exit)")
                    log("OK OpenClaw installed")
                } else {
                    log("OK OpenClaw already installed")
                }

                // Step 4: Workspace
                _progress.value = _progress.value.copy(step = "Setting up workspace...", progress = 0.92f)
                val ws = File(baseDir, "workspace")
                ws.mkdirs()
                mkfile(File(ws, "AGENTS.md"), "# AGENTS.md\n\nOpenClaw workspace.\n")
                mkfile(File(ws, "SOUL.md"), "# SOUL.md\n\nAI assistant on Android.\n")
                log("OK Workspace ready")

                // Done!
                _progress.value = SetupProgress(
                    isRunning = false, step = "Setup complete!", progress = 1f,
                    isComplete = true,
                    log = _progress.value.log + "DONE! Go back and start OpenClaw.\n"
                )

            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                _progress.value = _progress.value.copy(
                    isRunning = false, step = "Setup failed",
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
                log("-> Updating openclaw...")
                val exit = runCmdLines(
                    env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "update", "openclaw", "--prefix", baseDir.absolutePath
                ) { log("  $it") }
                if (exit != 0) throw Exception("Update failed (exit: $exit)")
                log("OK Done")
                _progress.value = SetupProgress(
                    isRunning = false, step = "Updated!", progress = 1f,
                    isComplete = true, log = _progress.value.log
                )
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    // Extract assets/[path] recursively to destDir
    private fun extractAssetsRecursive(assetPath: String, destDir: File) {
        var count = 0
        fun extract(aPath: String, dDir: File) {
            val list = context.assets.list(aPath) ?: return
            if (list.isEmpty()) {
                dDir.parentFile?.mkdirs()
                context.assets.open(aPath).use { inp ->
                    FileOutputStream(dDir).use { out -> inp.copyTo(out) }
                }
                if (dDir.name.endsWith(".js") || dDir.name.endsWith(".sh") || !dDir.name.contains(".")) {
                    dDir.setExecutable(true, false)
                }
                count++
            } else {
                dDir.mkdirs()
                for (item in list) extract("$aPath/$item", File(dDir, item))
            }
        }
        extract(assetPath, destDir)
        log("-> Extracted $count files")
    }

    // Environment: LD_LIBRARY_PATH points to termux-lib (versioned names) + nativeLibraryDir
    fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to baseDir.absolutePath,
        "PATH" to "$nativeLibDir:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to NativeLibHelper.getLibPath(context),
        "NODE_ENV" to "production",
        "TERM" to "xterm-256color",
        "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
        "npm_config_cache" to File(baseDir, ".npm-cache").apply { mkdirs() }.absolutePath,
        "npm_config_prefix" to baseDir.absolutePath
    )

    private fun runCmdOutput(env: Map<String, String>, vararg cmd: String): String {
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

    private fun runCmdLines(env: Map<String, String>, workDir: File, vararg cmd: String, onLine: (String) -> Unit = {}): Int {
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

    private fun mkfile(f: File, content: String) {
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(content) }
    }
}
