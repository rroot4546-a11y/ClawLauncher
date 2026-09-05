package com.roox.clawlauncher.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Direct access to OpenClaw's auth-profile store.
 *
 * OpenClaw 2026+ stores auth profiles inside its state SQLite database at
 *   ~/.openclaw/state/openclaw.sqlite  (HOME = baseDir)
 * in table `config_machine_state`:
 *   state_key  = "authProfiles.store"
 *   value_json = {"version":1,"profiles":{"<id>":{credential...}}}
 *
 * Why we write SQLite directly:
 * - The legacy `auth-profiles.json` file is only a *migration source*. If it
 *   exists while the state DB is already migrated, OpenClaw REFUSES to start
 *   ("requires legacy credential migration; run openclaw doctor --fix").
 *   Writing API keys/oauth to the legacy file after the first migration would
 *   therefore break the gateway on every save.
 * - Android has first-class SQLite APIs, so we upsert profiles natively.
 */
class AuthStoreManager(private val context: Context) {

    private val baseDir: File get() = File(context.filesDir, "openclaw")
    private val dotOpenclawDir: File get() = File(baseDir, ".openclaw")
    private val stateDir: File get() = File(dotOpenclawDir, "state")
    private val dbFile: File get() = File(stateDir, "openclaw.sqlite")

    companion object {
        private const val TABLE = "config_machine_state"
        private const val KEY_STORE = "authProfiles.store"
        private const val KEY_SHARED = "auth.sharedStore"
    }

    /**
     * Open (or create) the state DB and ensure the key/value table exists.
     * OpenClaw creates every other table itself through its own migrations;
     * `config_machine_state` uses CREATE TABLE IF NOT EXISTS semantics there
     * too, so creating it here is safe.
     */
    private fun openDb(): SQLiteDatabase {
        stateDir.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "state_key TEXT PRIMARY KEY NOT NULL, " +
                "value_json TEXT NOT NULL, " +
                "updated_at_ms INTEGER NOT NULL)"
        )
        return db
    }

    private fun readJson(db: SQLiteDatabase, key: String): JSONObject {
        val cursor = db.rawQuery("SELECT value_json FROM $TABLE WHERE state_key = ?", arrayOf(key))
        cursor.use {
            if (!it.moveToFirst()) return JSONObject("{\"version\":1,\"profiles\":{}}")
            return try { JSONObject(it.getString(0)) } catch (_: Exception) { JSONObject("{\"version\":1,\"profiles\":{}}") }
        }
    }

    private fun writeJson(db: SQLiteDatabase, key: String, value: JSONObject) {
        val cv = ContentValues().apply {
            put("state_key", key)
            put("value_json", value.toString())
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Read the whole profiles map from the store. */
    fun readProfiles(): JSONObject {
        return try {
            val db = openDb()
            try { readJson(db, KEY_STORE).optJSONObject("profiles") ?: JSONObject() }
            finally { db.close() }
        } catch (_: Exception) { JSONObject() }
    }

    /** True when the given profile id exists in the store. */
    fun hasProfile(profileId: String): Boolean = readProfiles().has(profileId)

    /**
     * Upsert a credential profile into the store and mark the DB as the
     * authoritative shared store. Other existing profiles are preserved.
     */
    fun upsertProfile(profileId: String, credential: JSONObject) {
        val db = openDb()
        try {
            val store = readJson(db, KEY_STORE)
            val profiles = store.optJSONObject("profiles") ?: JSONObject().also { store.put("profiles", it) }
            store.put("version", 1)
            profiles.put(profileId, credential)
            writeJson(db, KEY_STORE, store)
            writeJson(db, KEY_SHARED, JSONObject().put("location", "state-db"))
        } finally {
            db.close()
        }
    }

    /** Remove a profile from the store (no-op if store/profile absent). */
    fun removeProfile(profileId: String) {
        val db = openDb()
        try {
            val store = readJson(db, KEY_STORE)
            val profiles = store.optJSONObject("profiles") ?: return
            profiles.remove(profileId)
            writeJson(db, KEY_STORE, store)
        } finally {
            db.close()
        }
    }

    /** Update only the rotating fields of an existing oauth profile (token refresh). */
    fun refreshOAuthTokens(profileId: String, access: String, refresh: String?, expiresMs: Long, idToken: String? = null) {
        val db = openDb()
        try {
            val store = readJson(db, KEY_STORE)
            val profiles = store.optJSONObject("profiles") ?: return
            val cred = profiles.optJSONObject(profileId) ?: return
            cred.put("access", access)
            if (!refresh.isNullOrBlank()) cred.put("refresh", refresh)
            cred.put("expires", expiresMs)
            if (!idToken.isNullOrBlank()) cred.put("idToken", idToken)
            writeJson(db, KEY_STORE, store)
        } finally {
            db.close()
        }
    }

    /**
     * Legacy `auth-profiles.json` files make OpenClaw refuse to boot once the
     * state DB exists. Import any profiles they contain into SQLite, then
     * archive the files exactly like OpenClaw's own doctor would.
     */
    fun migrateLegacyFiles() {
        val candidates = listOf(
            File(dotOpenclawDir, "agents/main/agent/auth-profiles.json"),
            File(dotOpenclawDir, ".openclaw/agents/main/agent/auth-profiles.json"),
            File(baseDir, "agents/main/agent/auth-profiles.json")
        )
        for (file in candidates) {
            if (!file.exists()) continue
            try {
                val root = JSONObject(file.readText())
                val profiles = root.optJSONObject("profiles") ?: continue
                val keys = profiles.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val cred = profiles.optJSONObject(id) ?: continue
                    upsertProfile(id, cred)
                }
                val archive = File(file.parentFile,
                    file.name + ".migrated-by-clawlauncher-" + System.currentTimeMillis())
                file.renameTo(archive)
            } catch (_: Exception) {
                // If parsing fails, still move it out of the way so the gateway can boot.
                try {
                    val archive = File(file.parentFile,
                        file.name + ".invalid-by-clawlauncher-" + System.currentTimeMillis())
                    file.renameTo(archive)
                } catch (_: Exception) { file.delete() }
            }
        }
    }

    suspend fun migrateLegacyFilesIo() = withContext(Dispatchers.IO) { migrateLegacyFiles() }
}
