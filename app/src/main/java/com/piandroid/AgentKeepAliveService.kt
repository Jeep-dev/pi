package com.piandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "pi_agent_long_tasks"
        private const val NOTIFICATION_ID = 17649
        private const val ACTION_STOP = "com.piandroid.STOP_LONG_TASK_KEEPALIVE"
        private const val EXTRA_RECORDS = "com.piandroid.EXTRA_PI_SESSION_RECORDS"
        private const val MAX_WAKE_TIME_MS = 8L * 60 * 60 * 1000

        fun start(bridgeContext: Context, records: List<PiSessionRecord>? = null) {
            val intent = Intent(bridgeContext, AgentKeepAliveService::class.java)
            if (records != null) intent.putExtra(EXTRA_RECORDS, encodeRecords(records))
            bridgeContext.startForegroundService(intent)
        }

        private fun encodeRecords(records: List<PiSessionRecord>): String {
            val array = JSONArray()
            records.forEach { record ->
                array.put(
                    JSONObject()
                        .put("id", record.androidSessionId)
                        .put("name", record.name)
                        .put("cwd", record.cwd)
                        .put("launchCommand", record.launchCommand)
                        .put("port", record.port)
                        .put("token", record.token)
                        .put("startupArguments", record.startupArguments)
                        .put("sessionFile", record.sessionFile)
                        .put("piConversationId", record.piConversationId)
                        .put("status", record.status.name)
                        .put("lastActivity", record.lastActivity)
                        .put("lastError", record.lastError)
                        .put("displayName", record.displayName)
                        .put("ownedSessionFile", record.ownedSessionFile)
                        .put("sessionDirectory", record.sessionDirectory)
                        .put("pinned", record.pinned)
                        .put("legacySessionFile", record.legacySessionFile)
                )
            }
            return array.toString()
        }

        private fun decodeRecords(raw: String): List<PiSessionRecord> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val cwd = item.optString("cwd").trim()
                    val launchCommand = item.optString("launchCommand").trim()
                    val port = item.optInt("port")
                    val token = item.optString("token").trim()
                    if (id.isBlank() || cwd.isBlank() || launchCommand.isBlank() || port !in 1..65_535 || token.length < 32) continue
                    add(
                        PiSessionRecord(
                            androidSessionId = id,
                            name = item.optString("name").ifBlank { "Pi" },
                            cwd = cwd,
                            launchCommand = launchCommand,
                            port = port,
                            token = token,
                            startupArguments = item.optString("startupArguments"),
                            sessionFile = item.optString("sessionFile"),
                            piConversationId = item.optString("piConversationId"),
                            status = runCatching {
                                PiSessionStatus.valueOf(item.optString("status"))
                            }.getOrDefault(PiSessionStatus.UNKNOWN),
                            lastActivity = item.optLong("lastActivity"),
                            lastError = item.optString("lastError"),
                            displayName = item.optString("displayName"),
                            ownedSessionFile = item.optString("ownedSessionFile"),
                            sessionDirectory = item.optString("sessionDirectory"),
                            pinned = item.optBoolean("pinned"),
                            legacySessionFile = item.optBoolean("legacySessionFile")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        fun stop(bridgeContext: Context) {
            bridgeContext.stopService(Intent(bridgeContext, AgentKeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val recordsLock = Any()
    private var registeredRecords: List<PiSessionRecord> = emptyList()

    private fun recordsSnapshot(): List<PiSessionRecord> = synchronized(recordsLock) { registeredRecords }

    private fun replaceRecords(records: List<PiSessionRecord>) {
        synchronized(recordsLock) { registeredRecords = records }
        (application as PiApplication).runtimeManager.reconcile(records)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接本机 Pi Agent…"))

        // All open Pi Sessions are expected to remain runnable in the background.
        // Keep the CPU awake while the registry is non-empty so the watchdog and
        // Termux runtimes are not suspended behind the UI.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiAndroid:AllSessions")

        val records = PiSessionStore(applicationContext).loadOrCreateDefault()
        replaceRecords(records)

        monitorJob = scope.launch {
            while (isActive) {
                val currentRecords = recordsSnapshot()
                if (currentRecords.isEmpty()) {
                    wakeLock?.takeIf { it.isHeld }?.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                val runtimeManager = (application as PiApplication).runtimeManager
                runtimeManager.register(currentRecords)
                wakeLock?.takeUnless { it.isHeld }?.acquire(MAX_WAKE_TIME_MS)

                val probes = currentRecords.map { record ->
                    async {
                        val endpoint = PiBridge(
                            applicationContext,
                            record.port,
                            record.token,
                            record.androidSessionId
                        )
                        val healthResult = endpoint.health(timeoutMs = 1_200)
                        val health = healthResult.getOrNull()
                        val state = if (health?.piRunning == true) endpoint.state(timeoutMs = 1_500).getOrNull() else null
                        record to Triple(health, state, healthResult.exceptionOrNull())
                    }
                }.awaitAll()

                probes.forEach { (record, result) ->
                    val health = result.first
                    val healthError = result.third
                    if (health == null && !healthError.isSocketTimeoutFailure()) {
                        runtimeManager.recover(record, "Bridge 无响应，后台自动重连")
                    } else if (health != null && !health.piRunning) {
                        runtimeManager.recover(record, "Pi 进程已退出，后台自动重启")
                    }
                }

                val bridgeOnline = probes.count { (_, result) -> result.first != null }
                val working = probes.count { (_, result) ->
                    val state = result.second
                    state?.streaming == true || state?.compacting == true
                }
                val running = probes.count { (_, result) -> result.first?.piRunning == true }
                val unknown = probes.count { (_, result) ->
                    result.first == null && result.third.isSocketTimeoutFailure()
                }

                updateNotification(
                    when {
                        unknown > 0 ->
                            "$running/${currentRecords.size} 已确认在线 · $unknown 个状态未知"
                        running == currentRecords.size && working > 0 ->
                            "$working 个 Pi Agent 正在工作 · $running/${currentRecords.size} 个 Session 在线"
                        running == currentRecords.size ->
                            "$running 个 Pi Session 全部在线 · 后台保持运行"
                        else ->
                            "$running/${currentRecords.size} 个 Pi 在线 · $bridgeOnline/${currentRecords.size} 个 Bridge 在线 · 自动恢复中"
                    }
                )
                delay(10_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_RECORDS)?.let { raw ->
            replaceRecords(decodeRecords(raw))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pi 长程任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持 Pi Agent 在锁屏和后台运行"
                setShowBadge(false)
            }
        )
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopKeepAlive = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pi Android 长程任务")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止保活", stopKeepAlive)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }
}
