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
        private const val MAX_WAKE_TIME_MS = 8L * 60 * 60 * 1000

        fun start(bridgeContext: Context) {
            val intent = Intent(bridgeContext, AgentKeepAliveService::class.java)
            bridgeContext.startForegroundService(intent)
        }

        fun stop(bridgeContext: Context) {
            bridgeContext.stopService(Intent(bridgeContext, AgentKeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        (application as PiApplication).runtimeManager.register(records)

        monitorJob = scope.launch {
            val bridgeMisses = mutableMapOf<String, Int>()
            val piMisses = mutableMapOf<String, Int>()
            val lastRecoveryAt = mutableMapOf<String, Long>()
            while (isActive) {
                val currentRecords = PiSessionStore(applicationContext).loadOrCreateDefault()
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
                        val health = endpoint.health(timeoutMs = 1_200).getOrNull()
                        val state = if (health?.piRunning == true) endpoint.state(timeoutMs = 1_500).getOrNull() else null
                        record to (health to state)
                    }
                }.awaitAll()

                val now = android.os.SystemClock.elapsedRealtime()
                probes.forEach { (record, result) ->
                    val key = record.androidSessionId
                    val health = result.first
                    val cooldownElapsed = now - (lastRecoveryAt[key] ?: 0L) >= 30_000L

                    if (health == null) {
                        val misses = (bridgeMisses[key] ?: 0) + 1
                        bridgeMisses[key] = misses
                        piMisses[key] = 0
                        if (misses >= 3 && cooldownElapsed) {
                            lastRecoveryAt[key] = now
                            bridgeMisses[key] = 0
                            runtimeManager.recover(record, "Bridge 无响应，后台自动重连")
                        }
                    } else {
                        bridgeMisses[key] = 0
                        if (!health.piRunning) {
                            val misses = (piMisses[key] ?: 0) + 1
                            piMisses[key] = misses
                            // Bridge 2026-09-19.2 already auto-restarts its Pi child.
                            // Only escalate to Android-side recovery if that fails repeatedly.
                            if (misses >= 3 && cooldownElapsed) {
                                lastRecoveryAt[key] = now
                                piMisses[key] = 0
                                runtimeManager.recover(record, "Pi 进程持续离线，后台自动重连")
                            }
                        } else {
                            piMisses[key] = 0
                        }
                    }
                }

                val bridgeOnline = probes.count { (_, result) -> result.first != null }
                val working = probes.count { (_, result) ->
                    val state = result.second
                    state?.streaming == true || state?.compacting == true
                }
                val running = probes.count { (_, result) -> result.first?.piRunning == true }

                updateNotification(
                    when {
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
