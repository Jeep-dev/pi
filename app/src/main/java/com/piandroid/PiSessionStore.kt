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
    val startupArguments: String = "",
    val sessionFile: String = "",
    val piSessionId: String = "",
    val status: PiSessionStatus = PiSessionStatus.NOT_STARTED,
    val lastActivity: Long = 0L,
    val lastError: String = "",
    /** Android-only title. Pi's native session name must not overwrite it. */
    val displayName: String = "",
    /** The first Pi file created for this Android Session. Used for safe cleanup. */
    val ownedSessionFile: String = "",
    /** Private Pi session directory; never derived from cwd alone. */
    val sessionDirectory: String = "",
    val pinned: Boolean = false
)

internal fun sessionDisplayName(record: PiSessionRecord): String =
    record.displayName.trim().ifBlank { record.name.trim().ifBlank { "Pi" } }

/** Keep pinned records first while preserving the user's ordinary list order. */
internal fun orderPiSessions(records: List<PiSessionRecord>): List<PiSessionRecord> =
    records.withIndex()
        .sortedWith(compareBy<IndexedValue<PiSessionRecord>> { if (it.value.pinned) 0 else 1 }.thenBy { it.index })
        .map { it.value }

internal fun renamePiSession(
    records: List<PiSessionRecord>,
    id: String,
    displayName: String
): List<PiSessionRecord> = records.map { record ->
    if (record.id == id) record.copy(displayName = displayName.trim().replace(Regex("[\\r\\n]+"), " ")) else record
}

internal fun togglePiSessionPinned(records: List<PiSessionRecord>, id: String): List<PiSessionRecord> =
    orderPiSessions(records.map { record -> if (record.id == id) record.copy(pinned = !record.pinned) else record })

internal fun removePiSession(records: List<PiSessionRecord>, id: String): List<PiSessionRecord> =
    records.filterNot { it.id == id }

internal fun nextPiSessionIdAfterDelete(
    recordsAfterDelete: List<PiSessionRecord>,
    deletedId: String,
    activeId: String?
): String? = if (activeId == deletedId) recordsAfterDelete.firstOrNull()?.id else activeId

/** Small durable registry for Android's terminal-like Pi tabs. */
internal class PiSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("pi_sessions", Context.MODE_PRIVATE)

    fun loadOrCreateDefault(): List<PiSessionRecord> {
        val loaded = load()
        if (loaded.isNotEmpty()) {
            // Persist migrations (private session namespace, repaired ports/tokens,
            // and pinned/display metadata) before any runtime is started.
            save(loaded, activeId()?.takeIf { id -> loaded.any { it.id == id } })
            return loaded
        }
        val default = PiSessionRecord(
            id = DEFAULT_ID,
            name = "Pi",
            cwd = DEFAULT_CWD,
            launchCommand = defaultLaunchCommand(DEFAULT_ID),
            port = DEFAULT_PORT,
            token = PiBridge.endpointToken(context = appContext, key = DEFAULT_ID),
            piSessionId = DEFAULT_ID,
            sessionDirectory = sessionDirectory(DEFAULT_ID)
        )
        save(listOf(default), default.id)
        return listOf(default)
    }

    fun load(): List<PiSessionRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val usedIds = mutableSetOf<String>()
            val usedPorts = mutableSetOf<Int>()
            val loaded = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val cwd = item.optString("cwd").trim()
                    if (id.isBlank() || !VALID_ID.matches(id) || cwd.isBlank() || !usedIds.add(id)) continue

                    val preferredPort = item.optInt("port", if (id == DEFAULT_ID) DEFAULT_PORT else 0)
                    val port = if (preferredPort in 1..65_535 && usedPorts.add(preferredPort)) {
                        preferredPort
                    } else {
                        nextAvailablePort(usedPorts)
                    }
                    if (port !in 1..65_535) continue
                    usedPorts += port

                    val token = item.optString("token").trim().takeIf { it.length >= 32 }
                        ?: PiBridge.endpointToken(context = appContext, key = id)
                    val sessionFile = item.optString("sessionFile").trim()
                    val directory = item.optString("sessionDirectory").trim().ifBlank { sessionDirectory(id) }
                    val rawCommand = item.optString("launchCommand").ifBlank { defaultLaunchCommand(id) }
                    val command = ensurePiSessionIdentity(rawCommand, id, directory)
                    add(
                        PiSessionRecord(
                            id = id,
                            name = item.optString("name").ifBlank { "Pi" },
                            cwd = cwd,
                            launchCommand = command,
                            port = port,
                            token = token,
                            startupArguments = item.optString("startupArguments").trim(),
                            sessionFile = sessionFile,
                            piSessionId = item.optString("piSessionId").trim().ifBlank {
                                if (sessionFile.isBlank()) id else ""
                            },
                            // Process handles are intentionally not persisted. A new App
                            // instance probes each endpoint and replaces this transient value.
                            status = PiSessionStatus.NOT_STARTED,
                            lastActivity = item.optLong("lastActivity"),
                            lastError = item.optString("lastError"),
                            displayName = item.optString("displayName").trim(),
                            ownedSessionFile = item.optString("ownedSessionFile").trim().ifBlank { sessionFile },
                            sessionDirectory = directory,
                            pinned = item.optBoolean("pinned", false)
                        )
                    )
                }
            }
            val claimedFiles = mutableSetOf<String>()
            val isolated = loaded.map { record ->
                val file = record.sessionFile.replace('\\', '/')
                val privateMarker = "/.pi/android/sessions/${record.id}/pi-sessions/"
                val isPrivate = file.isBlank() || file.contains(privateMarker)
                val duplicate = file.isNotBlank() && isPrivate && !claimedFiles.add(file)
                val unsafeLegacyPointer = file.isNotBlank() && !isPrivate && record.id != DEFAULT_ID
                if ((unsafeLegacyPointer || duplicate) && file.isNotBlank()) {
                    // Old registries could point several Android records at Pi's
                    // cwd-wide latest session. Drop only the unsafe pointer; the
                    // old JSONL remains untouched and this record gets a new
                    // exact --session-id in its private directory.
                    record.copy(sessionFile = "", ownedSessionFile = "", piSessionId = record.id)
                } else {
                    record
                }
            }
            orderPiSessions(isolated)
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
                    .put("displayName", record.displayName)
                    .put("cwd", record.cwd)
                    .put("launchCommand", record.launchCommand)
                    .put("port", record.port)
                    .put("token", record.token)
                    .put("startupArguments", record.startupArguments)
                    .put("sessionFile", record.sessionFile)
                    .put("ownedSessionFile", record.ownedSessionFile)
                    .put("sessionDirectory", record.sessionDirectory)
                    .put("piSessionId", record.piSessionId)
                    .put("pinned", record.pinned)
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

    fun create(
        name: String,
        cwd: String,
        launchCommand: String,
        records: List<PiSessionRecord>,
        startupArguments: String = ""
    ): PiSessionRecord {
        val id = UUID.randomUUID().toString()
        val usedPorts = records.map { it.port }.toSet()
        val port = nextAvailablePort(usedPorts)
        check(port in 1..65_535) { "没有可用的 Pi Session 端口" }
        val visibleName = name.trim().ifBlank { "Pi ${records.size + 1}" }
        val directory = sessionDirectory(id)
        val inherited = launchCommand.trim()
        val baseCommand = if (
            inherited.isBlank() ||
            inherited == DEFAULT_LAUNCH_COMMAND ||
            inherited.contains("~/.pi/android/sessions/")
        ) {
            defaultLaunchCommand(id)
        } else {
            ensurePiSessionIdentity(inherited, id, directory)
        }
        return PiSessionRecord(
            id = id,
            name = visibleName,
            displayName = visibleName,
            cwd = cwd.trim().ifBlank { DEFAULT_CWD },
            launchCommand = baseCommand,
            port = port,
            token = PiBridge.endpointToken(context = appContext, key = id),
            startupArguments = startupArguments.trim(),
            piSessionId = id,
            sessionDirectory = directory
        )
    }

    fun delete(records: List<PiSessionRecord>, id: String, activeId: String?): List<PiSessionRecord> {
        val remaining = removePiSession(records, id)
        val nextActive = nextPiSessionIdAfterDelete(remaining, id, activeId)
        save(remaining, nextActive)
        return remaining
    }

    private fun nextAvailablePort(usedPorts: Set<Int>): Int {
        var port = NEW_SESSION_PORT
        while (port <= 65_535 && port in usedPorts) port++
        return port
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_PORT = 17649
        const val NEW_SESSION_PORT = 17650
        const val DEFAULT_CWD = "/data/data/com.termux/files/home"
        const val DEFAULT_LAUNCH_COMMAND = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts"
        private const val KEY_RECORDS = "records"
        private const val KEY_ACTIVE = "active"
        private val VALID_ID = Regex("[A-Za-z0-9_-]+")

        /** A private history namespace makes equal cwd values harmless. */
        fun sessionDirectory(id: String): String = "~/.pi/android/sessions/$id/pi-sessions"

        fun defaultLaunchCommand(id: String): String {
            val extension = if (id == DEFAULT_ID) {
                "~/.pi/android/pi-android-mobile.ts"
            } else {
                "~/.pi/android/sessions/$id/pi-android-mobile.ts"
            }
            return "pi --mode rpc -e $extension --session-dir ${sessionDirectory(id)} --session-id $id"
        }
    }
}
