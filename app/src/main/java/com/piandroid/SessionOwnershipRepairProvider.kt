package com.piandroid

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.util.UUID

/**
 * One-time repair for registries produced by older multi-session builds.
 *
 * Those builds could leave several Android Session records bound to conversations
 * that looked different in metadata but contained the same Pi history. Metadata-
 * only duplicate checks cannot reliably detect that state. Before MainActivity is
 * created, keep the user's currently active binding and detach every other Android
 * Session from its old Pi file. Detached records get a brand-new Pi conversation
 * id, so --session-id cannot reopen a contaminated file that already exists in the
 * Session's private directory. Old JSONL files are deliberately left untouched and
 * remain available through /resume.
 */
class SessionOwnershipRepairProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_REPAIR_VERSION, 0) >= REPAIR_VERSION) return true

        val raw = preferences.getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) {
            preferences.edit().putInt(KEY_REPAIR_VERSION, REPAIR_VERSION).commit()
            return true
        }

        runCatching {
            val records = JSONArray(raw)
            if (records.length() > 1) {
                val activeId = preferences.getString(KEY_ACTIVE, null).orEmpty()
                var keepIndex = -1
                if (activeId.isNotBlank()) {
                    for (index in 0 until records.length()) {
                        if (records.optJSONObject(index)?.optString("id") == activeId) {
                            keepIndex = index
                            break
                        }
                    }
                }
                if (keepIndex < 0) keepIndex = 0

                for (index in 0 until records.length()) {
                    if (index == keepIndex) continue
                    val record = records.optJSONObject(index) ?: continue
                    val androidSessionId = record.optString("id").trim()
                    if (androidSessionId.isBlank()) continue

                    val previousConversationId = record.optString("piConversationId")
                        .ifBlank { record.optString("piSessionId") }
                    val freshConversationId = UUID.randomUUID().toString()

                    record.put("sessionFile", "")
                    record.put("ownedSessionFile", "")
                    record.put("piConversationId", freshConversationId)
                    record.remove("piSessionId")
                    record.put("legacySessionFile", false)
                    record.put("status", "NOT_STARTED")
                    record.put("lastError", "")

                    Log.w(
                        TAG,
                        "DETACH_STALE_BINDING androidSessionId=$androidSessionId " +
                            "previousPiConversationId=$previousConversationId " +
                            "freshPiConversationId=$freshConversationId"
                    )
                }
            }

            check(
                preferences.edit()
                    .putString(KEY_RECORDS, records.toString())
                    .putInt(KEY_REPAIR_VERSION, REPAIR_VERSION)
                    .commit()
            ) { "Unable to persist Pi Session ownership repair" }
        }.onFailure { error ->
            // Do not mark the migration complete after a failure. The next cold
            // start will retry instead of silently keeping contaminated bindings.
            Log.e(TAG, "Pi Session ownership repair failed", error)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "PiSessionRepair"
        const val PREFERENCES = "pi_sessions"
        const val KEY_RECORDS = "records"
        const val KEY_ACTIVE = "active"
        const val KEY_REPAIR_VERSION = "ownership_repair_version"
        const val REPAIR_VERSION = 1
    }
}
