package com.piandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class PiSessionStatus {
    WORKING,
    IDLE,
    NOT_STARTED,
    ERROR
}

internal data class PiSessionRecord(
    val id: String,
    val name: String,
    val cwd: String,
    val launchCommand: String,
    val port: Int,
    val token: String,
    val sessionFile: String = "",
    val piSessionId: String = "",
    val status: PiSessionStatus = PiSessionStatus.NOT_STARTED,
    val lastActivity: Long = 0L,
    val lastError: String = ""
)

/** Small durable registry for Android's terminal-like Pi tabs. */
internal class PiSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("pi_sessions", Context.MODE_PRIVATE)

    fun loadOrCreateDefault(): List<PiSessionRecord> {
        val loaded = load().toMutableList()
        if (loaded.isNotEmpty()) return loaded
        val default = PiSessionRecord(
            id = DEFAULT_ID,
            name = "Pi",
            cwd = DEFAULT_CWD,
            launchCommand = defaultLaunchCommand(DEFAULT_ID),
            port = DEFAULT_PORT,
            token = PiBridge.endpointToken(context = appContext, key = "default")
        )
        save(listOf(default), default.id)
        return listOf(default)
    }

    fun load(): List<PiSessionRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val cwd = item.optString("cwd").trim()
                    val token = item.optString("token").trim()
                    val port = item.optInt("port", if (id == DEFAULT_ID) DEFAULT_PORT else 0)
                    if (id.isBlank() || !VALID_ID.matches(id) || cwd.isBlank() || token.isBlank() || port !in 1..65_535) continue
                    add(
                        PiSessionRecord(
                            id = id,
                            name = item.optString("name").ifBlank { "Pi" },
                            cwd = cwd,
                            launchCommand = item.optString("launchCommand").ifBlank { defaultLaunchCommand(id) },
                            port = port,
                            token = token,
                            sessionFile = item.optString("sessionFile"),
                            piSessionId = item.optString("piSessionId"),
                            // Process handles are intentionally not persisted. A new App
                            // instance will probe each endpoint and replace this transient
                            // value with the authoritative state.
                            status = PiSessionStatus.NOT_STARTED,
                            lastActivity = item.optLong("lastActivity"),
                            lastError = item.optString("lastError")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(): String? = preferences.getString(KEY_ACTIVE, null)

    fun save(records: List<PiSessionRecord>, activeId: String? = activeId()) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("cwd", record.cwd)
                    .put("launchCommand", record.launchCommand)
                    .put("port", record.port)
                    .put("token", record.token)
                    .put("sessionFile", record.sessionFile)
                    .put("piSessionId", record.piSessionId)
                    .put("status", record.status.name)
                    .put("lastActivity", record.lastActivity)
                    .put("lastError", record.lastError)
            )
        }
        preferences.edit()
            .putString(KEY_RECORDS, array.toString())
            .apply { if (activeId.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, activeId) }
            .commit()
    }

    fun create(name: String, cwd: String, launchCommand: String, records: List<PiSessionRecord>): PiSessionRecord {
        val id = UUID.randomUUID().toString()
        val usedPorts = records.map { it.port }.toSet()
        var port = NEW_SESSION_PORT
        while (port in usedPorts && port < 65_535) port++
        check(port < 65_535) { "没有可用的 Pi Session 端口" }
        return PiSessionRecord(
            id = id,
            name = name.trim().ifBlank { "Pi ${records.size + 1}" },
            cwd = cwd.trim().ifBlank { DEFAULT_CWD },
            launchCommand = launchCommand.trim().let { command ->
                if (command.isBlank() || command == DEFAULT_LAUNCH_COMMAND || command.contains("~/.pi/android/sessions/")) {
                    defaultLaunchCommand(id)
                } else {
                    command
                }
            },
            port = port,
            token = PiBridge.endpointToken(context = appContext, key = id)
        )
    }

    fun delete(records: List<PiSessionRecord>, id: String, activeId: String?): List<PiSessionRecord> {
        val remaining = records.filterNot { it.id == id }
        val nextActive = if (activeId == id) remaining.firstOrNull()?.id else activeId
        save(remaining, nextActive)
        return remaining
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_PORT = 17649
        const val NEW_SESSION_PORT = 17650
        const val DEFAULT_CWD = "/data/data/com.termux/files/home"
        const val DEFAULT_LAUNCH_COMMAND = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts"
        private const val KEY_RECORDS = "records"
        private val VALID_ID = Regex("[A-Za-z0-9_-]+")

        fun defaultLaunchCommand(id: String): String = if (id == DEFAULT_ID) {
            DEFAULT_LAUNCH_COMMAND
        } else {
            "pi --mode rpc -e ~/.pi/android/sessions/$id/pi-android-mobile.ts"
        }
        private const val KEY_ACTIVE = "active"
    }
}

