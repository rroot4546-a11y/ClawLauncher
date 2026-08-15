package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    val npmLine: String = "",
    val currentDownload: String = "",
    val downloadedCount: Int = 0,
    val totalEstimate: Int = 0,
    val retryAttempt: Int = 0,
    val downloadedBytes: Long = 0
)

data class OpenClawUpdateInfo(
    val isChecking: Boolean = false,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val checkedAt: Long? = null,
    val error: String? = null
)

class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress
    private val _updateInfo = MutableStateFlow(OpenClawUpdateInfo())
    val updateInfo: StateFlow<OpenClawUpdateInfo> = _updateInfo
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

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

    suspend fun checkForUpdates() {
        _updateInfo.value = _updateInfo.value.copy(isChecking = true, error = null)
        withContext(Dispatchers.IO) {
            try {
                val installed = getOpenClawVersion()
                val request = Request.Builder()
                    .url("https://registry.npmjs.org/openclaw/latest")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("npm registry returned HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val latest = JSONObject(body).optString("version").takeIf { it.isNotBlank() }
                        ?: throw Exception("Latest version was not found")
                    _updateInfo.value = OpenClawUpdateInfo(
                        isChecking = false,
                        installedVersion = installed,
                        latestVersion = latest,
                        isUpdateAvailable = installed == null || compareVersions(latest, installed) > 0,
                        checkedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                _updateInfo.value = _updateInfo.value.copy(
                    isChecking = false,
                    installedVersion = getOpenClawVersion(),
                    checkedAt = System.currentTimeMillis(),
                    error = e.message ?: "Could not check for updates"
                )
            }
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
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

                // Create android-patch.js FIRST (before any Node.js calls that use NODE_OPTIONS)
                val patchFile = File(baseDir, "android-patch.js")
                patchFile.writeText("""
const os = require('os');
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
                log("✓ Android network patch created")

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

                // Step 3: Install OpenClaw (with retry + resume)
                if (!isOpenClawInstalled) {
                    val maxRetries = 3
                    var lastExit = -1
                    var packagesAdded = 0
                    var downloadedPkgs = mutableSetOf<String>()
                    var fetchCount = 0

                    for (attempt in 1..maxRetries) {
                        _progress.value = _progress.value.copy(
                            step = if (attempt == 1) "Installing OpenClaw..." else "Retrying install (attempt $attempt/$maxRetries)...",
                            progress = 0.40f,
                            npmLine = "Resolving packages...",
                            retryAttempt = attempt,
                            downloadedCount = downloadedPkgs.size,
                            currentDownload = ""
                        )

                        if (attempt == 1) {
                            // Only clean on first attempt — preserve partial downloads for resume
                            File(baseDir, ".npm-cache").apply { if (!exists()) mkdirs() }
                        }

                        log(if (attempt == 1) "→ npm install openclaw..." else "→ Retry attempt $attempt/$maxRetries (resuming)...")
                        log("─".repeat(40))

                        lastExit = runCmdLines(env, baseDir,
                            nodeBin.absolutePath, npmCli.absolutePath,
                            "install", "openclaw",
                            "--prefix", baseDir.absolutePath,
                            "--no-audit", "--no-fund",
                            "--ignore-scripts", "--force",
                            "--prefer-offline"  // use cache for resume
                        ) { line ->
                            log("  $line")

                            // Track downloads with detailed info
                            when {
                                line.contains("http fetch GET") || (line.contains("GET") && line.contains("registry")) -> {
                                    val pkg = extractPackageName(line)
                                    if (pkg.isNotBlank()) {
                                        downloadedPkgs.add(pkg)
                                        fetchCount++
                                        _progress.value = _progress.value.copy(
                                            step = "Downloading packages...",
                                            currentDownload = pkg,
                                            npmLine = "📦 $pkg",
                                            downloadedCount = downloadedPkgs.size,
                                            progress = (0.40f + (fetchCount * 0.001f)).coerceAtMost(0.65f)
                                        )
                                    }
                                }
                                line.contains("http fetch 200") || line.contains("200 https://") -> {
                                    val pkg = extractPackageName(line)
                                    val size = Regex("""(\d+)ms""").find(line)?.groupValues?.get(1)
                                    if (pkg.isNotBlank()) {
                                        _progress.value = _progress.value.copy(
                                            npmLine = "✓ $pkg" + if (size != null) " (${size}ms)" else "",
                                            downloadedCount = downloadedPkgs.size
                                        )
                                    }
                                }
                                line.contains("reify:") -> {
                                    val pkg = line.substringAfter("reify:").trim().substringBefore(" ").take(40)
                                    _progress.value = _progress.value.copy(
                                        step = "Installing packages...",
                                        npmLine = "📥 $pkg",
                                        currentDownload = pkg,
                                        progress = (_progress.value.progress + 0.002f).coerceAtMost(0.80f)
                                    )
                                }
                                line.contains("added") && line.contains("package") -> {
                                    packagesAdded = Regex("""added (\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                    _progress.value = _progress.value.copy(
                                        step = "✅ Installed $packagesAdded packages!",
                                        progress = 0.85f,
                                        npmLine = "✅ Done!",
                                        currentDownload = "",
                                        downloadedCount = packagesAdded
                                    )
                                }
                                line.contains("WARN") || line.contains("warn") -> {
                                    // Show warnings but don't change progress
                                }
                                line.contains("ERR") || line.contains("error") -> {
                                    _progress.value = _progress.value.copy(
                                        npmLine = "⚠️ ${line.take(60)}"
                                    )
                                }
                            }
                        }

                        log("─".repeat(40))
                        log("→ Attempt $attempt exit code: $lastExit")

                        // Check if install succeeded (exit 0 or openclaw found)
                        if (lastExit == 0 || isOpenClawInstalled) {
                            log("✓ Install successful!")
                            break
                        }

                        if (attempt < maxRetries) {
                            log("→ Will retry in 2 seconds (cached packages will be reused)...")
                            _progress.value = _progress.value.copy(
                                step = "Retrying in 2s...",
                                npmLine = "🔄 Using cached downloads"
                            )
                            Thread.sleep(2000)
                        }
                    }

                    val exit = lastExit

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
                // Minimal config — only gateway settings
                // OpenClaw onboarding handles the rest (model, API key, channels)
                val configJson = """{
  "gateway": {
    "mode": "local",
    "bind": "loopback",
    "port": 3000
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

                // Create rootexec wrapper (available if root is enabled later)
                val logFile = File(baseDir, "root-exec.log")
                com.roox.clawlauncher.service.RootHelper.createRootExecScript(
                    File(baseDir, "bin"), logFile
                )
                log("✓ Root exec wrapper created")

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
        _progress.value = SetupProgress(isRunning = true, step = "Updating OpenClaw...", progress = 0.1f)
        withContext(Dispatchers.IO) {
            try {
                val env = buildEnv()
                val exit = runCmdLines(env, baseDir,
                    nodeBin.absolutePath, npmCli.absolutePath,
                    "install", "openclaw@latest",
                    "--prefix", baseDir.absolutePath,
                    "--no-audit", "--no-fund", "--ignore-scripts", "--force"
                ) { line ->
                    log("  $line")
                    _progress.value = _progress.value.copy(
                        step = "Updating OpenClaw...",
                        npmLine = line.take(100),
                        progress = (_progress.value.progress + 0.002f).coerceAtMost(0.92f)
                    )
                }
                if (exit != 0) throw Exception("Update failed (exit: $exit)")
                val entry = findOpenclawEntry()
                if (entry != null) saveOpenclawPath(entry.absolutePath)
                val installed = getOpenClawVersion()
                _updateInfo.value = _updateInfo.value.copy(
                    installedVersion = installed,
                    isUpdateAvailable = false,
                    error = null,
                    checkedAt = System.currentTimeMillis()
                )
                _progress.value = SetupProgress(
                    isRunning = false,
                    step = "OpenClaw updated${installed?.let { " to v$it" } ?: ""}!",
                    progress = 1f,
                    isComplete = true,
                    log = _progress.value.log
                )
            } catch (e: Exception) {
                log("❌ ${e.message}")
                _progress.value = _progress.value.copy(isRunning = false, error = e.message)
            }
        }
    }

    private fun extractPackageName(line: String): String {
        // Extract package name from npm fetch URLs like:
        // "http fetch GET https://registry.npmjs.org/openclaw"
        // "silly fetch GET https://registry.npmjs.org/@scope%2fpackage"
        val url = line.substringAfter("https://registry.npmjs.org/", "")
            .substringBefore("?").substringBefore(" ").substringBefore("\t")
        if (url.isBlank()) {
            // Try extracting from other URL patterns
            return line.substringAfterLast("/").substringBefore("?").substringBefore(" ").take(40)
        }
        return url.replace("%2f", "/").replace("%2F", "/").take(50)
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
        // HOME=baseDir → OpenClaw looks for config at baseDir/.openclaw/openclaw.json
        // Ensure .openclaw/ dir exists with config
        val dotDir = File(baseDir, ".openclaw")
        dotDir.mkdirs()
        val srcConfig = File(baseDir, "openclaw.json")
        val dstConfig = File(dotDir, "openclaw.json")
        if (srcConfig.exists()) {
            srcConfig.copyTo(dstConfig, overwrite = true)
        }
        // Workspace symlink inside .openclaw/
        val wsLink = File(dotDir, "workspace")
        val wsTarget = File(baseDir, "workspace")
        if (!wsLink.exists() && wsTarget.exists()) {
            try {
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", wsTarget.absolutePath, wsLink.absolutePath)).waitFor()
            } catch (_: Exception) { wsLink.mkdirs() }
        }
        return mapOf(
            "HOME" to baseDir.absolutePath,
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
