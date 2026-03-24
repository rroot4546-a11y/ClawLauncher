package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestorePoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val date: String = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date()),
    val sizeMb: Double = 0.0
)

class BackupManager(private val context: Context) {
    private val _restorePoints = MutableStateFlow<List<RestorePoint>>(emptyList())
    val restorePoints: StateFlow<List<RestorePoint>> = _restorePoints

    private val backupDir: File get() = File(context.filesDir, "backups")
    private val indexFile: File get() = File(backupDir, "index.json")
    private val openclawDir: File get() = File(context.filesDir, "openclaw")

    init {
        backupDir.mkdirs()
        loadIndex()
    }

    private fun loadIndex() {
        if (indexFile.exists()) {
            try {
                val json = JSONArray(indexFile.readText())
                val points = mutableListOf<RestorePoint>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    points.add(RestorePoint(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        date = obj.getString("date"),
                        sizeMb = obj.optDouble("sizeMb", 0.0)
                    ))
                }
                _restorePoints.value = points
            } catch (_: Exception) { }
        }
    }

    private fun saveIndex() {
        val json = JSONArray()
        _restorePoints.value.forEach { rp ->
            json.put(JSONObject().apply {
                put("id", rp.id)
                put("name", rp.name)
                put("description", rp.description)
                put("date", rp.date)
                put("sizeMb", rp.sizeMb)
            })
        }
        indexFile.writeText(json.toString(2))
    }

    suspend fun createRestorePoint(name: String, description: String): Result<RestorePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                val backupFile = File(backupDir, "$id.zip")

                // Create zip backup of workspace + config
                if (openclawDir.exists()) {
                    zipDirectory(openclawDir, backupFile, exclude = listOf("node_modules", "tmp", ".npm-cache"))
                }

                val sizeMb = if (backupFile.exists()) backupFile.length() / (1024.0 * 1024.0) else 0.0
                val point = RestorePoint(id = id, name = name, description = description, sizeMb = sizeMb)

                _restorePoints.value = _restorePoints.value + point
                saveIndex()
                Result.success(point)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun rollback(id: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val backupFile = File(backupDir, "$id.zip")
                if (!backupFile.exists()) return@withContext Result.failure(Exception("Backup not found"))

                // Delete current (except node_modules to save re-download)
                val workspace = File(openclawDir, "workspace")
                workspace.deleteRecursively()

                // Extract backup
                unzipFile(backupFile, openclawDir)

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun deleteRestorePoint(id: String) {
        withContext(Dispatchers.IO) {
            File(backupDir, "$id.zip").delete()
            _restorePoints.value = _restorePoints.value.filter { it.id != id }
            saveIndex()
        }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File, exclude: List<String> = emptyList()) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.walkTopDown()
                .filter { file -> exclude.none { ex -> file.absolutePath.contains("/$ex/") || file.name == ex } }
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.relativeTo(sourceDir).path
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun unzipFile(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val outFile = File(destDir, entry!!.name)
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) continue // path traversal guard
                if (entry!!.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
            }
        }
    }
}
