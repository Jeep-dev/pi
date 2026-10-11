package com.piandroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What Termux reports back for one RUN_COMMAND. Termux only answers when the
 * command could not start (errmsg) or when its process exited, so a Bridge that
 * keeps running never produces one.
 */
data class TermuxCommandResult(
    val exitCode: Int?,
    val err: Int?,
    val errmsg: String,
    val stdout: String,
    val stderr: String
)

/** Receives the result PendingIntent Termux fires for a launcher command. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val bundle = intent.getBundleExtra("result")
        val result = TermuxCommandResult(
            exitCode = bundle?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
            err = bundle?.takeIf { it.containsKey("err") }?.getInt("err"),
            errmsg = bundle?.getString("errmsg").orEmpty(),
            stdout = bundle?.getString("stdout").orEmpty(),
            stderr = bundle?.getString("stderr").orEmpty()
        )
        Log.w(
            "PiBridge",
            "TERMUX_RESULT id=$id exit=${result.exitCode} err=${result.err} errmsg=${result.errmsg.take(300)} stderr=${result.stderr.takeLast(300)}"
        )
        TermuxCommandResults.complete(id, result)
    }

    companion object {
        const val EXTRA_ID = "com.piandroid.termux_result_id"
    }
}

internal object TermuxCommandResults {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

    /** A fresh result slot and the PendingIntent Termux should fire into it. */
    fun register(context: Context): Pair<CompletableDeferred<TermuxCommandResult>, PendingIntent> {
        val id = nextId.getAndIncrement()
        // A Bridge that runs for days never answers; keep only recent slots.
        if (pending.size > 32) pending.keys.sorted().take(pending.size - 32).forEach { pending.remove(it) }
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pending[id] = deferred
        val intent = Intent(context, TermuxResultReceiver::class.java).putExtra(TermuxResultReceiver.EXTRA_ID, id)
        // Termux fills its result bundle into this intent, so it must stay mutable.
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return deferred to PendingIntent.getBroadcast(context, id, intent, flags)
    }

    fun complete(id: Int, result: TermuxCommandResult) {
        pending.remove(id)?.complete(result)
    }
}

/** Turn a Termux refusal or an early Bridge exit into something the user can act on. */
internal fun TermuxCommandResult.toFailure(logFile: String): Throwable {
    val text = "$errmsg\n$stderr"
    return when {
        text.contains("allow-external-apps") -> TermuxSetupException(
            "Termux 拒绝执行 Pi 的命令：Termux 没有开启 allow-external-apps。" +
                "在 Termux 里运行一次：mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings"
        )
        text.contains(LAUNCHER_NODE_MISSING) -> TermuxSetupException(
            "Termux 里找不到 node（Termux 数据可能被重装清空了）。在 Termux 里重新安装 nodejs 和 pi 后再连接"
        )
        errmsg.isNotBlank() -> TermuxSetupException("Termux 没能执行 Pi 的启动命令：${errmsg.trim().take(300)}")
        else -> IllegalStateException(
            "Bridge 刚启动就退出了（exit=${exitCode ?: "?"}）" +
                stderr.trim().takeLast(400).let { if (it.isBlank()) "" else "：$it" } +
                "。日志：$logFile"
        )
    }
}

internal const val LAUNCHER_NODE_MISSING = "PI_ANDROID_NODE_MISSING"
