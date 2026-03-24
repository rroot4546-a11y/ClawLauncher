package com.roox.clawlauncher.engine

import android.content.Context
import android.content.pm.ApplicationInfo
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
    private val nodeModulesDir: File get() = File(baseDir, "node_modules")

    /**
     * The node binary is bundled as libnode.so inside the APK's native libs.
     * Android allows executing native libs from nativeLibraryDir — this bypasses
     * the W^X restriction that blocks executing from app data directories.
     */
    val nodeBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    private val npmDir: File get() = File(baseDir, "npm")
    private val npmCli: File get() = File(npmDir, "npm/bin/npm-cli.js")
    private val openclawBin: File get() = File(nodeModulesDir, ".bin/openclaw")
    private val openclawMain: File get() = File(nodeModulesDir, "openclaw/bin/openclaw.js")

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

                // Step 1: Verify embedded Node.js binary
                _progress.value = _progress.value.copy(step = "Checking Node.js...", progress = 0.05f)
                log("→ Node binary: ${nodeBin.absolutePath}")
                log("→ Exists: ${nodeBin.exists()}, Executable: ${nodeBin.canExecute()}")

                if (!nodeBin.exists()) {
                    throw Exception("Node.js binary not found in APK native libs. This is a build error.")
                }

                // Test node execution
                val nodeVer = runCommandOutput(nodeBin.absolutePath, "--version")
                log("✓ Node.js version: $nodeVer")

                if (!nodeVer.startsWith("v")) {
                    throw Exception("Node.js failed to execute. Output: $nodeVer")
                }

                _progress.value = _progress.value.copy(progress = 0.15f)

                // Step 2: Extract npm from assets (bundled in APK)
                if (!isNpmInstalled) {
                    _progress.value = _progress.value.copy(step = "Setting up npm...", progress = 0.20f)
                    log("→ Extracting npm from assets...")

                    extractNpmFromAssets()
                    log("✓ npm ready")
                } else {
                    log("✓ npm already installed")
                }

                _progress.value = _progress.value.copy(progress = 0.35f)

                // Verify npm works
                val npmVer = runCommandWithEnvOutput(
                    buildEnv(),
                    nodeBin.absolutePath, npmCli.absolutePath, "--version"
                )
                log("✓ npm version: $npmVer")

                // Step 3: Install OpenClaw via npm
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw (this takes a few minutes)...",
                        progress = 0.40f
                    )
                    log("→ Installing openclaw...")

                    val env = buildEnv()
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
                                step = "Finalizing installation...",
                                progress = 0.85f
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

                _progress.value = _progress.value.copy(progress = 0.90f)

                // Step 4: Initialize workspace
                _progress.value = _progress.value.copy(step = "Setting up workspace...", progress = 0.92f)
                log("→ Initializing workspace...")

                val workspaceDir = File(baseDir, "workspace")
                workspaceDir.mkdirs()
                createFileIfMissing(File(workspaceDir, "AGENTS.md"), "# AGENTS.md\n\nYour OpenClaw workspace on Android.\n")
                createFileIfMissing(File(workspaceDir, "SOUL.md"), "# SOUL.md\n\nYou are a helpful AI assistant running on Android via ClawLauncher.\n")

                log("✓ Workspace ready")

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
                log("→ Updating openclaw...")
                val exitCode = runCommandWithEnv(
                    env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "update", "openclaw",
                    "--prefix", baseDir.absolutePath
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

    /**
     * Extract npm from APK assets to baseDir/npm/
     * npm is bundled as assets/npm/ directory in the APK during CI build
     */
    private fun extractNpmFromAssets() {
        val assetManager = context.assets
        npmDir.mkdirs()
        extractAssetDir("npm", npmDir)
    }

    private fun extractAssetDir(assetPath: String, destDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: return

        if (files.isEmpty()) {
            // It's a file, copy it
            assetManager.open(assetPath).use { input ->
                val outFile = destDir
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Make scripts executable
            if (destDir.name.endsWith(".js") || destDir.name.endsWith(".sh") || !destDir.name.contains(".")) {
                destDir.setExecutable(true, false)
            }
        } else {
            // It's a directory, recurse
            destDir.mkdirs()
            for (file in files) {
                extractAssetDir("$assetPath/$file", File(destDir, file))
            }
        }
    }

    private fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to baseDir.absolutePath,
        "PATH" to "${context.applicationInfo.nativeLibraryDir}:/system/bin:/system/xbin",
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
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(30, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun runCommandWithEnvOutput(env: Map<String, String>, vararg cmd: String): String {
        return try {
            val pb = ProcessBuilder(*cmd)
            pb.directory(baseDir)
            pb.environment().clear()
            pb.environment().putAll(env)
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
