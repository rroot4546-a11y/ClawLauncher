package com.roox.clawlauncher.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
                val backupFile = File(backupDir, "$id.tar.gz")

                // Create tar backup of openclaw directory
                if (openclawDir.exists()) {
                    val pb = ProcessBuilder(
                        "tar", "czf", backupFile.absolutePath, "-C",
                        openclawDir.parentFile!!.absolutePath, "openclaw"
                    )
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    proc.waitFor()
                }

                val sizeMb = if (backupFile.exists()) backupFile.length() / (1024.0 * 1024.0) else 0.0
                val point = RestorePoint(
                    id = id,
                    name = name,
                    description = description,
                    sizeMb = sizeMb
                )

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
                val backupFile = File(backupDir, "$id.tar.gz")
                if (!backupFile.exists()) return@withContext Result.failure(Exception("Backup not found"))

                // Delete current openclaw dir
                openclawDir.deleteRecursively()

                // Extract backup
                val pb = ProcessBuilder(
                    "tar", "xzf", backupFile.absolutePath, "-C",
                    openclawDir.parentFile!!.absolutePath
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun deleteRestorePoint(id: String) {
        withContext(Dispatchers.IO) {
            File(backupDir, "$id.tar.gz").delete()
            _restorePoints.value = _restorePoints.value.filter { it.id != id }
            saveIndex()
        }
    }
}
