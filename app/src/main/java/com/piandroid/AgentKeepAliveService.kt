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
import android.util.Log
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
            try {
                bridgeContext.startForegroundService(intent)
            } catch (error: IllegalStateException) {
                // Android 12+ refuses new foreground services from the background
                // (ForegroundServiceStartNotAllowedException). Reconnect loops call this
                // repeatedly, so a refusal must not crash the app.
                Log.w("AgentKeepAlive", "Keep-alive service start refused", error)
            }
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
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiAndroid:LongAgentTask")
            .apply { acquire(MAX_WAKE_TIME_MS) }
        monitorJob = scope.launch {
            val policy = KeepAlivePolicy()
            while (isActive) {
                wakeLock?.takeUnless { it.isHeld }?.acquire(MAX_WAKE_TIME_MS)
                val records = PiSessionStore(applicationContext).loadOrCreateDefault()
                val probes = records.map { record ->
                    async {
                        val endpoint = PiBridge(
                            applicationContext,
                            record.port,
                            record.token,
                            record.androidSessionId
                        )
                        val health = endpoint.health(timeoutMs = 2_500)
                        val piRunning = health.getOrNull()?.piRunning == true
                        val state = if (piRunning) endpoint.state(timeoutMs = 2_500) else null
                        val busy = state?.getOrNull()?.let { it.streaming || it.compacting } == true
                        classifyKeepAliveProbe(health.exceptionOrNull(), piRunning, state?.exceptionOrNull(), busy) to piRunning
                    }
                }.awaitAll()
                val working = probes.count { (probe, _) -> probe == KeepAliveProbe.WORKING }
                val running = probes.count { (_, piRunning) -> piRunning }
                val recovering = PiRecoveryTracker.anyRecovering()
                val uncertain = recovering || probes.any { (probe, _) -> probe == KeepAliveProbe.UNKNOWN }
                // An unreachable or reconnecting Session is not idle: stopping here
                // would release the wake lock exactly when recovery needs it.
                if (policy.shouldStop(probes.map { it.first }, recovering)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                updateNotification(
                    when {
                        working > 0 -> "$working 个 Pi Agent 正在工作 · 共 $running 个在线"
                        uncertain -> "正在重连 Pi · 共 $running 个在线"
                        running > 0 -> "$running 个 Pi Session 在线，当前空闲 · 即将停止保活"
                        else -> "没有在线 Pi · 即将停止保活"
                    }
                )
                delay(15_000)
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
