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
import java.util.zip.ZipInputStream

data class SetupProgress(
    val isRunning: Boolean = false,
    val step: String = "",
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val error: String? = null,
    val log: String = "",
    val npmLine: String = ""  // current npm activity line for UI
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

    // OpenClaw installed via npm — search recursively
    private val openclawMain: File? get() = findOpenclawJs()

    val isNodeInstalled: Boolean get() = nodeBin.exists()
    val isNpmInstalled: Boolean get() = npmCli.exists()
    val isOpenClawInstalled: Boolean get() = openclawMain != null

    /**
     * Recursively search for openclaw entry point under baseDir.
     * The binary is openclaw.mjs (not openclaw.js), main is dist/index.js
     */
    private fun findOpenclawJs(): File? {
        // Check known paths first (fast path)
        val knownPaths = listOf(
            // openclaw.mjs is the actual bin entry
            File(baseDir, "node_modules/openclaw/openclaw.mjs"),
            File(baseDir, "lib/node_modules/openclaw/openclaw.mjs"),
            // dist/index.js is the main entry
            File(baseDir, "node_modules/openclaw/dist/index.js"),
            File(baseDir, "lib/node_modules/openclaw/dist/index.js"),
            // Legacy paths just in case
            File(baseDir, "node_modules/openclaw/bin/openclaw.js"),
            File(baseDir, "lib/node_modules/openclaw/bin/openclaw.js"),
        )
        for (p in knownPaths) {
            if (p.exists()) return p
        }

        // Recursive search: look for openclaw.mjs first, then openclaw.js
        return findFileRecursive(baseDir, "openclaw.mjs", maxDepth = 8)
            ?: findFileRecursive(baseDir, "openclaw.js", maxDepth = 8)
    }

    /**
     * Find the openclaw main JS file path (for ProcessManager to use).
     */
    fun getOpenclawMainPath(): String? {
        return openclawMain?.absolutePath
    }

    private fun findFileRecursive(dir: File, name: String, maxDepth: Int, currentDepth: Int = 0): File? {
        if (currentDepth > maxDepth || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.name == name) return f
            if (f.isDirectory && f.name != ".npm-cache" && f.name != "tmp") {
                val found = findFileRecursive(f, name, maxDepth, currentDepth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun log(msg: String) {
        _progress.value = _progress.value.copy(log = _progress.value.log + msg + "\n")
    }

    /**
     * Log the file tree under a directory (for debugging installs).
     */
    private fun logFileTree(dir: File, prefix: String = "", maxDepth: Int = 4, currentDepth: Int = 0) {
        if (currentDepth > maxDepth || !dir.isDirectory) return
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        for (f in files) {
            if (f.name == ".npm-cache" || f.name == "tmp") continue
            val marker = if (f.isDirectory) "📁" else "📄"
            log("$prefix$marker ${f.name}" + if (f.isFile) " (${formatSize(f.length())})" else "")
            if (f.isDirectory) {
                logFileTree(f, "$prefix  ", maxDepth, currentDepth + 1)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))}MB"
        }
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
                log("→ Node binary path: ${nodeBin.absolutePath}")
                log("→ nativeLibraryDir: $nativeLibDir")
                log("→ Exists: ${nodeBin.exists()}")

                if (!nodeBin.exists()) {
                    throw Exception("Node.js (libnode.so) not found in native libs. Reinstall the APK.")
                }

                // List native libs available
                val nativeFiles = File(nativeLibDir).listFiles()?.map { it.name }?.sorted() ?: emptyList()
                log("→ Native libs (${nativeFiles.size}): ${nativeFiles.joinToString()}")

                // Create bin/ directory with symlinks so "node" and "npm" are in PATH
                val binDir = File(baseDir, "bin")
                binDir.mkdirs()
                createSymlink(nodeBin, File(binDir, "node"))
                log("→ Created bin/node → ${nodeBin.absolutePath}")

                // Test node execution
                log("→ Testing node --version...")
                val env = buildEnv()
                val nodeVer = runCmdOutput(env, nodeBin.absolutePath, "--version")
                log("→ Result: $nodeVer")

                if (!nodeVer.startsWith("v")) {
                    throw Exception("Node.js failed to start. Output: $nodeVer")
                }

                log("✓ Node.js $nodeVer working!")
                _progress.value = _progress.value.copy(progress = 0.25f)

                // Step 2: Extract npm from ZIP asset
                if (!isNpmInstalled) {
                    _progress.value = _progress.value.copy(step = "Setting up npm...", progress = 0.30f)
                    // List available assets for debugging
                    val assets = context.assets.list("") ?: emptyArray()
                    log("→ Assets root: ${assets.joinToString()}")
                    log("→ Extracting npm from npm.zip...")
                    npmDir.mkdirs()
                    extractZipAsset("npm.zip", npmDir)
                    // Create bin/npm symlink
                    val npmBinScript = File(npmDir, "bin/npm-cli.js")
                    if (npmBinScript.exists()) {
                        val npmWrapper = File(binDir, "npm")
                        npmWrapper.writeText("#!/system/bin/sh\nexec \"${nodeBin.absolutePath}\" \"${npmBinScript.absolutePath}\" \"$@\"\n")
                        npmWrapper.setExecutable(true, false)
                        log("→ Created bin/npm wrapper")
                    }
                    log("✓ npm extracted (${npmDir.listFiles()?.size ?: 0} top-level items)")
                } else {
                    log("✓ npm already installed")
                }

                // Verify npm
                val npmVer = runCmdOutput(env, nodeBin.absolutePath, npmCli.absolutePath, "--version")
                log("→ npm version: $npmVer")
                _progress.value = _progress.value.copy(progress = 0.40f)

                // Step 3: Install OpenClaw
                log("→ Pre-install check:")
                log("   node_modules/: ${File(baseDir, "node_modules/openclaw/bin/openclaw.js").exists()}")
                log("   lib/node_modules/: ${File(baseDir, "lib/node_modules/openclaw/bin/openclaw.js").exists()}")
                log("   findOpenclawJs: ${openclawMain?.absolutePath ?: "NOT FOUND"}")
                log("   ls baseDir: ${baseDir.listFiles()?.joinToString { it.name } ?: "empty"}")

                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw...",
                        progress = 0.45f,
                        npmLine = "Resolving packages..."
                    )

                    // Clean npm cache to prevent ENOTEMPTY errors
                    log("→ Cleaning npm cache...")
                    val cacheDir = File(baseDir, ".npm-cache")
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                    }
                    // Clean old node_modules if partial install
                    val oldModules = File(baseDir, "node_modules")
                    if (oldModules.exists() && !isOpenClawInstalled) {
                        log("→ Cleaning old node_modules...")
                        oldModules.deleteRecursively()
                    }

                    log("→ npm install openclaw --prefix ${baseDir.absolutePath}")
                    log("─".repeat(50))

                    var packagesAdded = 0
                    var lastProgressUpdate = 0.45f

                    val exit = runCmdLines(
                        env, baseDir,
                        nodeBin.absolutePath, npmCli.absolutePath,
                        "install", "openclaw",
                        "--prefix", baseDir.absolutePath,
                        "--no-optional", "--no-audit", "--no-fund",
                        "--ignore-scripts", "--force",
                        "--loglevel", "verbose"
                    ) { line ->
                        log("  $line")

                        // Parse npm output for UI feedback
                        val npmActivity = parseNpmLine(line)
                        if (npmActivity != null) {
                            _progress.value = _progress.value.copy(npmLine = npmActivity)
                        }

                        // Progress estimation based on npm output patterns
                        when {
                            line.contains("http fetch GET") || line.contains("silly fetch") -> {
                                // Fetching packages
                                val newProgress = (lastProgressUpdate + 0.002f).coerceAtMost(0.70f)
                                lastProgressUpdate = newProgress
                                _progress.value = _progress.value.copy(
                                    step = "Downloading packages...",
                                    progress = newProgress
                                )
                            }
                            line.contains("http fetch 200") || line.contains("silly fetch 200") -> {
                                val newProgress = (lastProgressUpdate + 0.003f).coerceAtMost(0.75f)
                                lastProgressUpdate = newProgress
                                _progress.value = _progress.value.copy(progress = newProgress)
                            }
                            line.contains("idealTree") || line.contains("reify") -> {
                                val newProgress = (lastProgressUpdate + 0.005f).coerceAtMost(0.80f)
                                lastProgressUpdate = newProgress
                                _progress.value = _progress.value.copy(
                                    step = "Installing dependencies...",
                                    progress = newProgress
                                )
                            }
                            line.contains("added") && line.contains("package") -> {
                                // e.g. "added 312 packages in 45s"
                                val match = Regex("""added (\d+) package""").find(line)
                                packagesAdded = match?.groupValues?.get(1)?.toIntOrNull() ?: packagesAdded
                                _progress.value = _progress.value.copy(
                                    step = "Installed $packagesAdded packages!",
                                    progress = 0.88f,
                                    npmLine = "Finalizing..."
                                )
                            }
                            line.contains("npm warn") || line.contains("WARN") -> {
                                // Show warnings but don't change progress
                            }
                            line.contains("npm error") || line.contains("ERR!") -> {
                                _progress.value = _progress.value.copy(
                                    npmLine = "⚠️ ${line.take(80)}"
                                )
                            }
                        }
                    }

                    log("─".repeat(50))
                    log("→ npm exit code: $exit")

                    if (exit != 0) {
                        log("→ npm install failed, checking if openclaw was installed anyway...")
                    }

                    // Robust post-install check: search recursively
                    log("→ Post-install recursive search for openclaw.js:")
                    val foundPath = findOpenclawJs()
                    if (foundPath != null) {
                        log("✓ Found openclaw.js at: ${foundPath.absolutePath}")
                    } else {
                        log("✗ openclaw.js NOT found anywhere under ${baseDir.absolutePath}")
                    }

                    // Log the file tree for debugging
                    log("→ File tree under baseDir:")
                    logFileTree(baseDir, "  ", maxDepth = 3)

                    // Also check for openclaw directory (even if bin/openclaw.js missing)
                    val openclawDir = findDirectoryRecursive(baseDir, "openclaw", 6)
                    if (openclawDir != null && foundPath == null) {
                        log("→ Found openclaw dir at: ${openclawDir.absolutePath} but no openclaw.js")
                        log("→ Contents: ${openclawDir.listFiles()?.joinToString { it.name } ?: "empty"}")
                    }

                    if (exit != 0 && foundPath == null) {
                        throw Exception("npm install openclaw failed (exit: $exit)")
                    }

                    log("✓ OpenClaw installed" + if (packagesAdded > 0) " ($packagesAdded packages)" else "")
                } else {
                    log("✓ OpenClaw already installed at: ${openclawMain?.absolutePath}")
                }

                // Step 4: Workspace
                _progress.value = _progress.value.copy(step = "Setting up workspace...", progress = 0.92f, npmLine = "")
                val ws = File(baseDir, "workspace")
                ws.mkdirs()
                mkfile(File(ws, "AGENTS.md"), "# AGENTS.md\n\nOpenClaw workspace.\n")
                mkfile(File(ws, "SOUL.md"), "# SOUL.md\n\nAI assistant on Android.\n")
                log("✓ Workspace ready")

                // Done!
                _progress.value = SetupProgress(
                    isRunning = false, step = "Setup complete!", progress = 1f,
                    isComplete = true,
                    log = _progress.value.log + "✓ DONE! Go back and start OpenClaw.\n",
                    npmLine = ""
                )

            } catch (e: Exception) {
                log("❌ ERROR: ${e.message}")
                _progress.value = _progress.value.copy(
                    isRunning = false, step = "Setup failed",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Parse an npm output line into a human-readable activity string.
     */
    private fun parseNpmLine(line: String): String? {
        return when {
            // Package fetch: "http fetch GET https://registry.npmjs.org/openclaw"
            line.contains("http fetch GET") -> {
                val url = line.substringAfter("http fetch GET").trim()
                val pkg = url.substringAfterLast("/").substringBefore("?").substringBefore(" ")
                if (pkg.isNotBlank()) "📦 Fetching $pkg..." else null
            }
            // Verbose fetch: "silly fetch GET https://..."
            line.contains("silly fetch") && line.contains("GET") -> {
                val url = line.substringAfter("GET").trim()
                val pkg = url.substringAfterLast("/").substringBefore("?").substringBefore(" ")
                if (pkg.isNotBlank() && !pkg.startsWith("-")) "📦 Fetching $pkg..." else null
            }
            // Package resolution
            line.contains("idealTree") -> "🔍 Resolving dependency tree..."
            // Reification (actual install)
            line.contains("reify:") -> {
                val pkg = line.substringAfter("reify:").trim().substringBefore(" ")
                if (pkg.isNotBlank()) "📥 Installing $pkg..." else "📥 Installing packages..."
            }
            line.contains("reify") && !line.contains("reify:") -> "📥 Installing packages..."
            // Added packages
            line.contains("added") && line.contains("package") -> {
                "✅ ${line.trim()}"
            }
            // Timing info
            line.contains("timing") -> null
            // Warnings
            line.contains("npm warn") -> "⚠️ ${line.substringAfter("npm warn").trim().take(60)}"
            else -> null
        }
    }

    private fun findDirectoryRecursive(dir: File, name: String, maxDepth: Int, currentDepth: Int = 0): File? {
        if (currentDepth > maxDepth || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory && f.name == name) return f
            if (f.isDirectory && f.name != ".npm-cache" && f.name != "tmp") {
                val found = findDirectoryRecursive(f, name, maxDepth, currentDepth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    suspend fun updateOpenClaw() {
        _progress.value = SetupProgress(isRunning = true, step = "Updating...", progress = 0.1f)
        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                log("→ Updating openclaw...")
                val exit = runCmdLines(
                    env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "update", "openclaw", "--prefix", baseDir.absolutePath
                ) { line ->
                    log("  $line")
                    val npmActivity = parseNpmLine(line)
                    if (npmActivity != null) {
                        _progress.value = _progress.value.copy(npmLine = npmActivity)
                    }
                }
                if (exit != 0) throw Exception("Update failed (exit: $exit)")
                log("✓ Done")
                _progress.value = SetupProgress(
                    isRunning = false, step = "Updated!", progress = 1f,
                    isComplete = true, log = _progress.value.log
                )
            } catch (e: Exception) {
                log("❌ ERROR: ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    // Extract a ZIP file from assets to destDir
    private fun extractZipAsset(assetName: String, destDir: File) {
        var count = 0
        context.assets.open(assetName).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zip.copyTo(out)
                        }
                        // Make scripts executable
                        if (outFile.name.endsWith(".js") || outFile.name.endsWith(".sh") || !outFile.name.contains(".")) {
                            outFile.setExecutable(true, false)
                        }
                        count++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        log("→ Extracted $count files from $assetName")
    }

    // Environment: bin/ has node+npm symlinks, nativeLibraryDir has the actual binaries
    fun buildEnv(): Map<String, String> {
        val binDir = File(baseDir, "bin").absolutePath
        return mapOf(
            "HOME" to baseDir.absolutePath,
            "PATH" to "$binDir:$nativeLibDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeLibDir,
            "NODE_ENV" to "production",
            "TERM" to "xterm-256color",
            "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "npm_config_cache" to File(baseDir, ".npm-cache").apply { mkdirs() }.absolutePath,
            "npm_config_prefix" to baseDir.absolutePath
        )
    }

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

    private fun createSymlink(target: File, link: File) {
        try {
            if (link.exists()) link.delete()
            // Try OS symlink
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", target.absolutePath, link.absolutePath)).waitFor()
            if (!link.exists()) {
                // Fallback: create a shell wrapper
                link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"$@\"\n")
                link.setExecutable(true, false)
            }
        } catch (_: Exception) {
            // Last resort: shell wrapper
            link.parentFile?.mkdirs()
            link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"$@\"\n")
            link.setExecutable(true, false)
        }
    }

    private fun mkfile(f: File, content: String) {
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(content) }
    }

    /**
     * Get the disk usage of the openclaw directory in bytes.
     */
    fun getDiskUsage(): Long {
        return getDirSize(baseDir)
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) getDirSize(f) else f.length()
        }
        return size
    }

    /**
     * Get the installed OpenClaw version by reading package.json
     */
    fun getOpenClawVersion(): String? {
        // Find the openclaw package.json
        val knownPaths = listOf(
            File(baseDir, "node_modules/openclaw/package.json"),
            File(baseDir, "lib/node_modules/openclaw/package.json"),
        )
        for (p in knownPaths) {
            if (p.exists()) {
                try {
                    val json = p.readText()
                    val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(json)
                    return match?.groupValues?.get(1)
                } catch (_: Exception) { }
            }
        }
        // Fallback: search recursively
        val openclawJs = findOpenclawJs() ?: return null
        val packageJson = File(openclawJs.parentFile?.parentFile, "package.json")
        if (packageJson.exists()) {
            try {
                val json = packageJson.readText()
                val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(json)
                return match?.groupValues?.get(1)
            } catch (_: Exception) { }
        }
        return null
    }
}
