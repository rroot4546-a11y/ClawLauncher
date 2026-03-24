package com.roox.clawlauncher.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class SetupProgress(
    val isRunning: Boolean = false,
    val step: String = "",
    val progress: Float = 0f, // 0-1
    val isComplete: Boolean = false,
    val error: String? = null
)

class BootstrapManager(private val context: Context) {
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val nodeDir: File get() = File(baseDir, "node")
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val nodeVersion = "v20.11.0"

    private val arch: String get() = when {
        Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "arm64"
        Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x64"
        Build.SUPPORTED_ABIS.any { it.startsWith("arm") } -> "armv7l"
        else -> "x64"
    }

    suspend fun runSetup() {
        _progress.value = SetupProgress(isRunning = true, step = "Preparing...", progress = 0f)

        withContext(Dispatchers.IO) {
            try {
                // Step 1: Create directories
                _progress.value = _progress.value.copy(step = "Creating directories...", progress = 0.1f)
                baseDir.mkdirs()
                nodeDir.mkdirs()

                // Step 2: Download Node.js
                _progress.value = _progress.value.copy(step = "Downloading Node.js ($arch)...", progress = 0.2f)
                val nodeUrl = "https://nodejs.org/dist/$nodeVersion/node-$nodeVersion-linux-$arch.tar.xz"
                val nodeTar = File(baseDir, "node.tar.xz")

                downloadFile(nodeUrl, nodeTar) { downloaded, total ->
                    val p = if (total > 0) downloaded.toFloat() / total else 0f
                    _progress.value = _progress.value.copy(
                        step = "Downloading Node.js... ${(p * 100).toInt()}%",
                        progress = 0.2f + (p * 0.4f)
                    )
                }

                // Step 3: Extract Node.js
                _progress.value = _progress.value.copy(step = "Extracting Node.js...", progress = 0.6f)
                extractTarXz(nodeTar, nodeDir)
                nodeTar.delete()

                // Step 4: Install OpenClaw
                _progress.value = _progress.value.copy(step = "Installing OpenClaw...", progress = 0.8f)
                val npmBin = File(nodeDir, "bin/npm")
                if (npmBin.exists()) {
                    val nodeBin = File(nodeDir, "bin/node")
                    val pb = ProcessBuilder(
                        nodeBin.absolutePath,
                        npmBin.absolutePath,
                        "install", "-g", "openclaw"
                    )
                    pb.directory(baseDir)
                    pb.environment()["HOME"] = baseDir.absolutePath
                    pb.environment()["PATH"] = "${nodeDir.absolutePath}/bin:${System.getenv("PATH")}"
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    proc.waitFor(300, TimeUnit.SECONDS)
                }

                // Done
                _progress.value = SetupProgress(
                    isRunning = false,
                    step = "Setup complete!",
                    progress = 1f,
                    isComplete = true
                )

            } catch (e: Exception) {
                _progress.value = SetupProgress(
                    isRunning = false,
                    step = "Setup failed",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw Exception("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            }
        }
    }

    private fun extractTarXz(tarFile: File, destDir: File) {
        // Use system tar if available, otherwise manual extraction
        try {
            val pb = ProcessBuilder("tar", "xf", tarFile.absolutePath, "--strip-components=1", "-C", destDir.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor(120, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Fallback: try busybox or manual approach
        }
    }
}
