package com.roox.clawlauncher.service

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootHelper {
    private const val TAG = "ClawRoot"

    // Check if device is rooted
    fun isRooted(): Boolean {
        return checkSuBinary() || checkMagisk() || checkKernelSU()
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su",
            "/sbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/su/bin/su",
            "/magisk/.core/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkMagisk(): Boolean {
        return File("/data/adb/magisk").exists() ||
               File("/sbin/.magisk").exists()
    }

    private fun checkKernelSU(): Boolean {
        return File("/data/adb/ksu").exists()
    }

    // Request root permission (shows su prompt)
    fun requestRoot(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val output = reader.readText()
            proc.waitFor()
            val granted = proc.exitValue() == 0 && output.contains("uid=0")
            Log.i(TAG, "Root request: ${if (granted) "GRANTED" else "DENIED"}")
            granted
        } catch (e: Exception) {
            Log.w(TAG, "Root request failed: ${e.message}")
            false
        }
    }

    // Execute a command with root
    fun execRoot(command: String, timeoutMs: Long = 30000): RootResult {
        Log.i(TAG, "Root exec: $command")
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(proc.inputStream))
            val stderr = BufferedReader(InputStreamReader(proc.errorStream))

            // Read with timeout
            val outputThread = Thread {
                try { stdout.readLines() } catch (_: Exception) {}
            }
            outputThread.start()
            outputThread.join(timeoutMs)

            val output = stdout.readText()
            val error = stderr.readText()
            val exitCode = try { proc.exitValue() } catch (_: Exception) { proc.destroyForcibly(); -1 }

            Log.i(TAG, "Root result: exit=$exitCode, output=${output.take(200)}")
            RootResult(exitCode, output, error)
        } catch (e: Exception) {
            Log.e(TAG, "Root exec error: ${e.message}")
            RootResult(-1, "", e.message ?: "Unknown error")
        }
    }

    // Create rootexec wrapper script for OpenClaw
    fun createRootExecScript(binDir: File, logFile: File) {
        binDir.mkdirs()
        val script = File(binDir, "rootexec")
        script.writeText("""#!/system/bin/sh
# ClawLauncher Root Exec Wrapper
# Usage: rootexec <command> [args...]
LOG="${logFile.absolutePath}"
CMD="${'$'}@"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] ROOT: ${'$'}CMD" >> "${'$'}LOG"
su -c "${'$'}CMD" 2>&1
EXIT=${'$'}?
echo "[$(date '+%Y-%m-%d %H:%M:%S')] EXIT: ${'$'}EXIT" >> "${'$'}LOG"
exit ${'$'}EXIT
""")
        script.setExecutable(true, false)

        // Also create a direct su wrapper
        val suWrapper = File(binDir, "su-exec")
        suWrapper.writeText("""#!/system/bin/sh
su -c "${'$'}@"
""")
        suWrapper.setExecutable(true, false)
    }

    data class RootResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success get() = exitCode == 0
        val output get() = stdout.ifBlank { stderr }
    }
}
