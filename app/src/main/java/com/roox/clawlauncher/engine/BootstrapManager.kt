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
    val npmLine: String = ""
)

class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress

    val baseDir: File get() = File(context.filesDir, "openclaw")
    val nodeBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir
    private val npmDir: File get() = File(baseDir, "npm")
    private val npmCli: File get() = File(npmDir, "bin/npm-cli.js")

    // Saved path file — the DEFINITIVE answer for where openclaw lives
    private val openclawPathFile: File get() = File(baseDir, ".openclaw-path")

    val isNodeInstalled: Boolean get() = nodeBin.exists()
    val isNpmInstalled: Boolean get() = npmCli.exists()

    // DEFINITIVE check: is OpenClaw installed?
    val isOpenClawInstalled: Boolean get() {
        // Method 1: saved path file
        val savedPath = getSavedOpenclawPath()
        if (savedPath != null && File(savedPath).exists()) return true
        // Method 2: package.json exists (openclaw dir exists)
        if (findOpenclawDir() != null) return true
        return false
    }

    // Get the main JS file to execute
    fun getOpenclawMainPath(): String? {
        // Method 1: saved path
        val saved = getSavedOpenclawPath()
        if (saved != null && File(saved).exists()) return saved
        // Method 2: search
        val found = findOpenclawEntry()
        if (found != null) {
            saveOpenclawPath(found.absolutePath)
            return found.absolutePath
        }
        return null
    }

    fun getOpenClawVersion(): String? {
        val dir = findOpenclawDir() ?: return null
        val pkg = File(dir, "package.json")
        if (!pkg.exists()) return null
        return try {
            val text = pkg.readText()
            Regex(""""version"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    fun getDiskUsage(): Long {
        return dirSize(baseDir)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0
    }

    // Find the openclaw directory (contains package.json)
    private fun findOpenclawDir(): File? {
        val candidates = listOf(
            File(baseDir, "node_modules/openclaw"),
            File(baseDir, "lib/node_modules/openclaw")
        )
        for (c in candidates) {
            if (c.isDirectory && File(c, "package.json").exists()) return c
        }
        // Recursive search
        return findDirWithFile(baseDir, "openclaw", "package.json", 6)
    }

    // Find the CLI entry point (openclaw.mjs is the CLI, dist/index.js is the library)
    private fun findOpenclawEntry(): File? {
        val dir = findOpenclawDir() ?: return null
        // CLI entry point MUST be openclaw.mjs (the bin entry)
        // dist/index.js is the library main, NOT the CLI
        val entries = listOf(
            File(dir, "openclaw.mjs"),      // THE correct CLI entry
            File(dir, "bin/openclaw.js"),    // Legacy fallback
            File(dir, "openclaw.js")         // Another fallback
        )
        return entries.firstOrNull { it.exists() }
    }

    private fun getSavedOpenclawPath(): String? {
        return try {
            if (openclawPathFile.exists()) openclawPathFile.readText().trim().takeIf { it.isNotEmpty() }
            else null
        } catch (_: Exception) { null }
    }

    private fun saveOpenclawPath(path: String) {
        try { openclawPathFile.writeText(path) } catch (_: Exception) {}
    }

    private fun findDirWithFile(root: File, dirName: String, fileName: String, maxDepth: Int, depth: Int = 0): File? {
        if (depth > maxDepth || !root.isDirectory) return null
        val files = root.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory && f.name == dirName && File(f, fileName).exists()) return f
            if (f.isDirectory && f.name != ".npm-cache" && f.name != "tmp" && f.name != ".git") {
                val found = findDirWithFile(f, dirName, fileName, maxDepth, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

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

                // Step 1: Node.js
                _progress.value = _progress.value.copy(step = "Checking Node.js...", progress = 0.05f)
                log("→ Node: ${nodeBin.absolutePath}")
                if (!nodeBin.exists()) throw Exception("Node.js not found. Reinstall APK.")

                val binDir = File(baseDir, "bin")
                binDir.mkdirs()
                createSymlink(nodeBin, File(binDir, "node"))

                val env = buildEnv()
                val nodeVer = runCmdOutput(env, nodeBin.absolutePath, "--version")
                if (!nodeVer.startsWith("v")) throw Exception("Node.js failed: $nodeVer")
                log("✓ Node.js $nodeVer")
                _progress.value = _progress.value.copy(progress = 0.20f)

                // Step 2: npm
                if (!isNpmInstalled) {
                    _progress.value = _progress.value.copy(step = "Setting up npm...", progress = 0.25f)
                    npmDir.mkdirs()
                    extractZipAsset("npm.zip", npmDir)
                    val npmBin = File(npmDir, "bin/npm-cli.js")
                    if (npmBin.exists()) {
                        File(binDir, "npm").apply {
                            writeText("#!/system/bin/sh\nexec \"${nodeBin.absolutePath}\" \"${npmBin.absolutePath}\" \"$@\"\n")
                            setExecutable(true, false)
                        }
                    }
                }
                val npmVer = runCmdOutput(env, nodeBin.absolutePath, npmCli.absolutePath, "--version")
                log("✓ npm $npmVer")
                _progress.value = _progress.value.copy(progress = 0.35f)

                // Step 3: Install OpenClaw
                if (!isOpenClawInstalled) {
                    _progress.value = _progress.value.copy(
                        step = "Installing OpenClaw...", progress = 0.40f,
                        npmLine = "Resolving packages..."
                    )

                    // Clean before install
                    File(baseDir, ".npm-cache").apply { if (exists()) deleteRecursively(); mkdirs() }
                    val nm = File(baseDir, "node_modules")
                    if (nm.exists()) nm.deleteRecursively()
                    val libNm = File(baseDir, "lib/node_modules")
                    if (libNm.exists()) libNm.deleteRecursively()

                    log("→ npm install openclaw...")
                    log("─".repeat(40))

                    var packagesAdded = 0

                    val exit = runCmdLines(env, baseDir,
                        nodeBin.absolutePath, npmCli.absolutePath,
                        "install", "openclaw",
                        "--prefix", baseDir.absolutePath,
                        "--no-audit", "--no-fund",
                        "--ignore-scripts", "--force"
                    ) { line ->
                        log("  $line")
                        // Update UI with npm activity
                        parseNpmLine(line)?.let { activity ->
                            _progress.value = _progress.value.copy(npmLine = activity)
                        }
                        // Progress estimation
                        when {
                            line.contains("http fetch") || line.contains("GET https://") ->
                                _progress.value = _progress.value.copy(
                                    step = "Downloading packages...",
                                    progress = (_progress.value.progress + 0.001f).coerceAtMost(0.65f)
                                )
                            line.contains("reify") ->
                                _progress.value = _progress.value.copy(
                                    step = "Installing...",
                                    progress = (_progress.value.progress + 0.002f).coerceAtMost(0.80f)
                                )
                            line.contains("added") && line.contains("package") -> {
                                packagesAdded = Regex("""added (\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                _progress.value = _progress.value.copy(
                                    step = "Installed $packagesAdded packages!",
                                    progress = 0.85f, npmLine = "✅ Done!"
                                )
                            }
                        }
                    }

                    log("─".repeat(40))
                    log("→ Exit code: $exit")

                    // Find the CLI entry point (openclaw.mjs, NOT dist/index.js)
                    log("→ Searching for openclaw CLI entry point...")

                    // Also find the openclaw directory and look for entry points
                    val clawDir = findOpenclawDir()
                    if (clawDir != null) {
                        log("→ OpenClaw dir: ${clawDir.absolutePath}")
                        log("→ Contents: ${clawDir.listFiles()?.take(20)?.joinToString { it.name }}")
                        val entry = findOpenclawEntry()
                        if (entry != null) {
                            saveOpenclawPath(entry.absolutePath)
                            log("✓ Entry point: ${entry.absolutePath}")
                        }
                    } else {
                        log("→ Searching all dirs...")
                        baseDir.listFiles()?.forEach { f ->
                            log("  ${if (f.isDirectory) "📁" else "📄"} ${f.name}")
                        }
                        File(baseDir, "lib").listFiles()?.forEach { f ->
                            log("  lib/${f.name}")
                        }
                        File(baseDir, "lib/node_modules").listFiles()?.forEach { f ->
                            log("  lib/node_modules/${f.name}")
                        }
                    }

                    if (!isOpenClawInstalled && exit != 0) {
                        throw Exception("npm install failed (exit: $exit)")
                    }

                    val version = getOpenClawVersion()
                    log("✓ OpenClaw installed" + (if (version != null) " v$version" else "") +
                        (if (packagesAdded > 0) " ($packagesAdded packages)" else ""))
                } else {
                    log("✓ OpenClaw already installed: ${getOpenclawMainPath()}")
                }

                // Step 4: Config + Workspace
                _progress.value = _progress.value.copy(step = "Finalizing...", progress = 0.92f, npmLine = "")

                // Create openclaw config in both locations
                val configJson = """{
  "gateway": {
    "mode": "local",
    "bind": "loopback",
    "port": 3000
  },
  "agents": {
    "defaults": {
      "model": {
        "primary": "openrouter/anthropic/claude-sonnet-4"
      }
    }
  }
}"""
                val configFile = File(baseDir, "openclaw.json")
                if (!configFile.exists()) configFile.writeText(configJson)
                // Also put in .openclaw/ (where OpenClaw actually looks)
                val dotDir = File(baseDir, ".openclaw")
                dotDir.mkdirs()
                val dotConfig = File(dotDir, "openclaw.json")
                if (!dotConfig.exists()) dotConfig.writeText(configJson)
                // Workspace inside .openclaw/
                val dotWs = File(dotDir, "workspace")
                if (!dotWs.exists()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("ln", "-sf",
                            File(baseDir, "workspace").absolutePath, dotWs.absolutePath)).waitFor()
                    } catch (_: Exception) { dotWs.mkdirs() }
                }
                log("✓ Config created")

                val ws = File(baseDir, "workspace")
                ws.mkdirs()
                mkfile(File(ws, "AGENTS.md"), "# AGENTS.md\n\nOpenClaw workspace.\n")
                mkfile(File(ws, "SOUL.md"), "# SOUL.md\n\nAI assistant on Android.\n")
                log("✓ Workspace ready")

                // Done!
                _progress.value = SetupProgress(
                    isRunning = false, step = "Setup complete!", progress = 1f,
                    isComplete = true,
                    log = _progress.value.log + "✓ DONE! Go back and start OpenClaw.\n"
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

    suspend fun updateOpenClaw() {
        _progress.value = SetupProgress(isRunning = true, step = "Updating...", progress = 0.1f)
        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                val exit = runCmdLines(env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "update", "openclaw", "--prefix", baseDir.absolutePath
                ) { log("  $it") }
                if (exit != 0) throw Exception("Update failed (exit: $exit)")
                // Re-resolve path
                val entry = findOpenclawEntry()
                if (entry != null) saveOpenclawPath(entry.absolutePath)
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

    private fun parseNpmLine(line: String): String? {
        return when {
            line.contains("http fetch GET") || (line.contains("GET") && line.contains("https://")) -> {
                val pkg = line.substringAfterLast("/").substringBefore("?").substringBefore(" ").take(40)
                if (pkg.isNotBlank() && pkg.length > 1) "📦 $pkg" else null
            }
            line.contains("reify:") -> {
                val pkg = line.substringAfter("reify:").trim().substringBefore(" ").take(40)
                if (pkg.isNotBlank()) "📥 $pkg" else "📥 Installing..."
            }
            line.contains("added") && line.contains("package") -> "✅ ${line.trim().take(50)}"
            else -> null
        }
    }

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
                        FileOutputStream(outFile).use { out -> zip.copyTo(out) }
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
        log("→ Extracted $count files")
    }

    fun buildEnv(): Map<String, String> {
        val binDir = File(baseDir, "bin").absolutePath
        // OpenClaw looks for config at $HOME/.openclaw/ or $OPENCLAW_HOME/
        // We create .openclaw/ inside baseDir and point HOME there
        val openclawHome = File(baseDir, ".openclaw").apply { mkdirs() }
        // Copy/link openclaw.json to .openclaw/ if it exists in baseDir
        val srcConfig = File(baseDir, "openclaw.json")
        val dstConfig = File(openclawHome, "openclaw.json")
        if (srcConfig.exists() && !dstConfig.exists()) {
            srcConfig.copyTo(dstConfig, overwrite = true)
        }
        // Also ensure workspace symlink exists in .openclaw/
        val wsLink = File(openclawHome, "workspace")
        val wsTarget = File(baseDir, "workspace")
        if (!wsLink.exists() && wsTarget.exists()) {
            try {
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", wsTarget.absolutePath, wsLink.absolutePath)).waitFor()
            } catch (_: Exception) {
                // Fallback: just create the dir
                wsLink.mkdirs()
            }
        }
        return mapOf(
            "HOME" to baseDir.absolutePath,
            "OPENCLAW_HOME" to openclawHome.absolutePath,
            "XDG_CONFIG_HOME" to baseDir.absolutePath,
            "PATH" to "$binDir:$nativeLibDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeLibDir,
            "NODE_ENV" to "production",
            "NODE_OPTIONS" to "--unhandled-rejections=warn",
            "TERM" to "xterm-256color",
            "TMPDIR" to File(baseDir, "tmp").apply { mkdirs() }.absolutePath,
            "npm_config_cache" to File(baseDir, ".npm-cache").apply { mkdirs() }.absolutePath,
            "npm_config_prefix" to baseDir.absolutePath,
            "NODE_PATH" to "${baseDir.absolutePath}/lib/node_modules:${baseDir.absolutePath}/node_modules",
            "OPENCLAW_MDNS" to "false",
            "OPENCLAW_BONJOUR" to "false"
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
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", target.absolutePath, link.absolutePath)).waitFor()
            if (!link.exists()) {
                link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"$@\"\n")
                link.setExecutable(true, false)
            }
        } catch (_: Exception) {
            link.parentFile?.mkdirs()
            link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"$@\"\n")
            link.setExecutable(true, false)
        }
    }

    private fun mkfile(f: File, content: String) {
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(content) }
    }
}
