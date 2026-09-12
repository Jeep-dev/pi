package com.piandroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** A snapshot delivered to a UI client without making that client own the runtime. */
internal data class PiRuntimeReady(
    val state: PiState,
    val snapshot: PiRecoverySnapshot,
    val runtimeCwd: String = "",
    val reconnecting: Boolean = false
)

/** Bind the mutable Pi conversation while preserving Android Session identity. */
internal fun bindPiConversation(
    record: PiSessionRecord,
    state: PiState,
    fallbackSessionFile: String
): PiSessionRecord {
    val selectedFile = state.sessionFile.ifBlank { fallbackSessionFile }
    return record.copy(
        sessionFile = selectedFile,
        piSessionId = state.sessionId.ifBlank { record.piSessionId },
        legacySessionFile = selectedFile.isNotBlank() && !isPrivatePiSessionFile(record.id, selectedFile),
        status = if (state.streaming || state.compacting) PiSessionStatus.WORKING else PiSessionStatus.IDLE,
        lastActivity = if (state.streaming || state.compacting) System.currentTimeMillis() else record.lastActivity,
        lastError = ""
    )
}

internal sealed class PiRuntimeUpdate {
    data class Ready(val value: PiRuntimeReady) : PiRuntimeUpdate()
    data class Snapshot(val value: PiRecoverySnapshot) : PiRuntimeUpdate()
    data class Events(val value: PiEventBatch) : PiRuntimeUpdate()
    data class Reconnecting(val error: Throwable) : PiRuntimeUpdate()
    data class Failed(val error: Throwable) : PiRuntimeUpdate()
    object Unavailable : PiRuntimeUpdate()
    object Disconnected : PiRuntimeUpdate()
}

/**
 * Per-session cancellation fence. Jobs submitted through this gate are the only
 * jobs Stop is allowed to cancel; the runtime's bridge/event jobs live outside it.
 */
internal class PiTaskGate {
    private val lock = Any()
    private var accepting = true
    private var generation = 0L
    private val jobs = LinkedHashSet<Job>()

    fun isAccepting(): Boolean = synchronized(lock) { accepting }

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job? {
        synchronized(lock) {
            if (!accepting) return null
            val jobGeneration = generation
            val job = scope.launch {
                if (!isGenerationActive(jobGeneration)) return@launch
                try {
                    block()
                } finally {
                    currentCoroutineContext()[Job]?.let { finished ->
                        synchronized(lock) { jobs.remove(finished) }
                    }
                }
            }
            jobs += job
            if (!job.isActive) jobs.remove(job)
            return job
        }
    }

    /** Fence first, then cancel every locally queued/running task. */
    fun beginStop() {
        val toCancel = synchronized(lock) {
            accepting = false
            generation++
            jobs.toList()
        }
        toCancel.forEach { it.cancel(CancellationException("Pi task stopped")) }
    }

    /** A later user prompt may start only after the remote stop has completed. */
    fun finishStop() = synchronized(lock) { accepting = true }

    fun close() {
        val toCancel = synchronized(lock) {
            accepting = false
            generation++
            jobs.toList()
        }
        toCancel.forEach { it.cancel(CancellationException("Pi runtime closed")) }
    }

    private fun isGenerationActive(expected: Long): Boolean = synchronized(lock) {
        accepting && generation == expected
    }
}

/**
 * Owns one authenticated Bridge endpoint and its Pi RPC lifecycle. This object
 * is created by MainActivity, not by Compose, and its SupervisorJob is never a
 * child of a composable coroutine. A tab switch therefore cannot cancel Pi,
 * event polling, reconnect, or queued task cancellation.
 */
internal class PiSessionRuntime(
    initialRecord: PiSessionRecord,
    parentJob: Job,
    bridge: PiBridge
) {
    val id: String = initialRecord.id
    val bridge: PiBridge = bridge

    // Main keeps UI callbacks safe; every PiBridge HTTP/file operation switches
    // to IO internally, while this scope remains independent of composition.
    private val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private val taskGate = PiTaskGate()
    private val updatesMutable = MutableSharedFlow<PiRuntimeUpdate>(
        // Keep the latest runtime update so a newly composed client can
        // immediately bind to an already-running Session.
        replay = 1,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<PiRuntimeUpdate> = updatesMutable.asSharedFlow()

    private val stateLock = Any()
    private var record = initialRecord
    private var closed = false
    private var connected = false
    private var autoStartRequested = false
    private var recoveryEnabled = true
    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var stopJob: Deferred<Result<Unit>>? = null
    private var eventCursor = 0L
    private var conversationGeneration = 0L
    private var lastState: PiState? = null
    private var lastSnapshot: PiRecoverySnapshot? = null

    fun update(next: PiSessionRecord) {
        synchronized(stateLock) {
            if (!closed) record = next
        }
    }

    fun isConnected(): Boolean = synchronized(stateLock) { connected }

    fun canSubmitTask(): Boolean = taskGate.isAccepting()

    fun launchTask(block: suspend () -> Unit): Job? = taskGate.launch(scope, block)

    /** Request a connection without making the caller's coroutine own the work. */
    fun ensureConnected(next: PiSessionRecord, autoStart: Boolean) {
        update(next)
        synchronized(stateLock) {
            if (closed) return
            if (autoStart) {
                autoStartRequested = true
                recoveryEnabled = true
            }
            if (connected) {
                val state = lastState
                val snapshot = lastSnapshot
                if (state != null && snapshot != null) {
                    scope.launch { updatesMutable.emit(PiRuntimeUpdate.Ready(PiRuntimeReady(state, snapshot))) }
                }
                return
            }
            if (connectionJob?.isActive == true) return
            launchConnectionLocked()
        }
    }

    /** Explicitly tell the runtime not to restart after /quit. */
    fun disableRecovery() {
        synchronized(stateLock) {
            recoveryEnabled = false
            autoStartRequested = false
        }
    }

    fun stopCurrentAgent(): Deferred<Result<Unit>> {
        synchronized(stateLock) {
            stopJob?.takeIf { it.isActive }?.let { return it }
            val job = scope.async {
                taskGate.beginStop()
                try {
                    bridge.stop()
                } finally {
                    taskGate.finishStop()
                    synchronized(stateLock) { stopJob = null }
                }
            }
            stopJob = job
            return job
        }
    }

    /** Switch the Pi conversation while preserving this Android Session runtime. */
    suspend fun switchPiConversation(sessionPath: String): Result<PiRuntimeReady> = runCatching {
        connectMutex.withLock {
            synchronized(stateLock) {
                check(!closed) { "Pi Session runtime is closed" }
                check(connected) { "Pi Session is not connected" }
                // Invalidate any in-flight event poll for the old conversation.
                conversationGeneration++
            }
            val switched = bridge.switchSession(sessionPath).getOrThrow()
            val snapshot = bridge.recoverySnapshot().getOrThrow()
            synchronized(stateLock) {
                check(!closed) { "Pi Session runtime is closed" }
                connected = true
                lastState = switched
                lastSnapshot = snapshot
                eventCursor = snapshot.latest
                record = bindPiConversation(record, switched, sessionPath)
            }
            val ready = PiRuntimeReady(
                state = switched,
                snapshot = snapshot,
                runtimeCwd = currentRecord().cwd,
                reconnecting = false
            )
            // Publish the new snapshot as the runtime's latest binding. A newly
            // composed screen must not replay the pre-resume snapshot.
            updatesMutable.emit(PiRuntimeUpdate.Ready(ready))
            ready
        }
    }

    fun closeAndCleanup(ownedSessionFile: String): Job {
        synchronized(stateLock) {
            if (closed) return scope.launch { }
            closed = true
            connected = false
            recoveryEnabled = false
            autoStartRequested = false
            connectionJob?.cancel()
            eventJob?.cancel()
            taskGate.close()
        }
        return scope.launch {
            bridge.shutdownAndCleanup(ownedSessionFile)
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun launchConnectionLocked() {
        val startIfMissing = autoStartRequested
        val requestedRecord = record
        connectionJob = scope.launch {
            var result = runCatching { connectInternal(requestedRecord, startIfMissing) }
            // If an inactive tab was being probed while the user selected it,
            // promote that same runtime to an active start instead of waiting for
            // a second Compose effect.
            if (result.isFailure && result.exceptionOrNull() is RuntimeUnavailable) {
                val promote = synchronized(stateLock) { autoStartRequested && !startIfMissing && !closed }
                if (promote) result = runCatching { connectInternal(currentRecord(), true) }
            }
            if (result.isSuccess) {
                publishReady(result.getOrThrow())
                startEventLoop()
            } else {
                val error = result.exceptionOrNull() ?: IllegalStateException("Pi connection failed")
                if (error is RuntimeUnavailable) updatesMutable.emit(PiRuntimeUpdate.Unavailable)
                else updatesMutable.emit(PiRuntimeUpdate.Failed(error))
            }
            val retryAsActive = synchronized(stateLock) {
                connectionJob = null
                !closed && !connected && autoStartRequested && !startIfMissing
            }
            if (retryAsActive) {
                synchronized(stateLock) {
                    if (connectionJob == null && !closed) launchConnectionLocked()
                }
            }
        }
    }

    private suspend fun connectInternal(
        configuredRecord: PiSessionRecord,
        allowStart: Boolean
    ): PiRuntimeReady = connectMutex.withLock {
        connectInternalLocked(configuredRecord, allowStart)
    }

    private suspend fun connectCurrent(
        allowStart: Boolean,
        expectedGeneration: Long? = null
    ): PiRuntimeReady? = connectMutex.withLock {
        if (expectedGeneration != null && !isConversationGeneration(expectedGeneration)) return@withLock null
        connectInternalLocked(currentRecord(), allowStart)
    }

    private suspend fun connectInternalLocked(
        configuredRecord: PiSessionRecord,
        allowStart: Boolean
    ): PiRuntimeReady {
        val healthBefore = bridge.health(timeoutMs = 2_500).getOrNull()
        val attached = bridge.attachToRunningBridge().getOrNull()
        val runningHealth = healthBefore ?: bridge.health(timeoutMs = 2_500).getOrNull()
        if (attached != null) {
            val runningCwd = runningHealth?.cwd.orEmpty()
            if (sameCwd(configuredRecord.cwd, runningCwd)) {
                val snapshot = bridge.recoverySnapshot().getOrThrow()
                return PiRuntimeReady(
                    state = attached,
                    snapshot = snapshot,
                    runtimeCwd = runningCwd
                )
            }
            if (!allowStart) throw RuntimeUnavailable()
        } else if (!allowStart) {
            throw RuntimeUnavailable()
        }

        val previous = bridge.state(timeoutMs = 1_500).getOrNull()
        val previousFile = previous?.sessionFile?.takeIf { it.isNotBlank() }
            ?: lastState?.sessionFile?.takeIf { it.isNotBlank() }
            ?: configuredRecord.sessionFile.takeIf { it.isNotBlank() }
        val preserveSession = previousFile != null &&
            (runningHealth?.cwd.isNullOrBlank() || sameCwd(configuredRecord.cwd, runningHealth?.cwd.orEmpty()))

        if (previous != null && !previous.streaming && !previous.compacting) {
            bridge.commands().getOrDefault(emptyList())
                .firstOrNull { it.name == "__android_checkpoint" }
                ?.let { bridge.prompt("/__android_checkpoint") }
        }
        bridge.installAndStartBridge().getOrThrow()
        bridge.waitForBridge(30_000).getOrThrow()

        val configuredLaunch = appendPiStartupArguments(
            configuredRecord.launchCommand.trim(),
            configuredRecord.startupArguments
        )
        val sessionDirectory = configuredRecord.sessionDirectory.ifBlank {
            PiSessionStore.sessionDirectory(configuredRecord.id)
        }
        val launch = if (preserveSession && previousFile != null) {
            bridge.recoveryLaunchCommand(configuredLaunch, previousFile)
        } else {
            bridge.freshLaunchCommand(
                configuredLaunch,
                configuredRecord.piSessionId.ifBlank { configuredRecord.id },
                sessionDirectory
            )
        }
        val started = bridge.start(configuredRecord.cwd.trim(), launch).getOrThrow()
        val runtimeHealth = bridge.health(timeoutMs = 8_000).getOrThrow()
        val snapshot = bridge.recoverySnapshot().getOrThrow()
        return PiRuntimeReady(
            state = started,
            snapshot = snapshot,
            runtimeCwd = runtimeHealth.cwd,
            reconnecting = true
        )
    }

    private fun publishReady(ready: PiRuntimeReady, expectedGeneration: Long? = null): Boolean {
        synchronized(stateLock) {
            if (closed || (expectedGeneration != null && conversationGeneration != expectedGeneration)) return false
            connected = true
            lastState = ready.state
            lastSnapshot = ready.snapshot
            eventCursor = ready.snapshot.latest
        }
        scope.launch { updatesMutable.emit(PiRuntimeUpdate.Ready(ready)) }
        return true
    }

    private fun startEventLoop() {
        synchronized(stateLock) {
            if (closed || eventJob?.isActive == true) return
            eventJob = scope.launch { eventLoop() }
        }
    }

    private suspend fun eventLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !isClosed() && isConnected()) {
            val (cursor, generation) = synchronized(stateLock) { eventCursor to conversationGeneration }
            val result = bridge.events(cursor)
            if (!isConversationGeneration(generation)) continue
            if (result.isSuccess) {
                val batch = result.getOrThrow()
                failures = 0
                if (batch.gap) {
                    val snapshot = bridge.recoverySnapshot()
                    if (!isConversationGeneration(generation)) continue
                    if (snapshot.isSuccess) {
                        val value = snapshot.getOrThrow()
                        synchronized(stateLock) { eventCursor = value.latest; lastSnapshot = value }
                        updatesMutable.emit(PiRuntimeUpdate.Snapshot(value))
                    } else {
                        failures++
                        updatesMutable.emit(PiRuntimeUpdate.Reconnecting(snapshot.exceptionOrNull()!!))
                        delay(500L)
                    }
                } else {
                    synchronized(stateLock) { eventCursor = batch.latest }
                    if (batch.events.any {
                            it.uiRequest?.method == "notify" && it.uiRequest.message == "ANDROID_PI_QUIT"
                        }) {
                        disableRecovery()
                    }
                    updatesMutable.emit(PiRuntimeUpdate.Events(batch))
                    if (batch.events.any { it.type == "process_exit" }) {
                        if (!canRecover()) {
                            synchronized(stateLock) { connected = false }
                            updatesMutable.emit(PiRuntimeUpdate.Disconnected)
                            break
                        }
                        recoverFromEventFailure(generation)
                    }
                }
            } else {
                if (!isConversationGeneration(generation)) continue
                val error = result.exceptionOrNull() ?: IllegalStateException("Bridge event polling failed")
                failures++
                updatesMutable.emit(PiRuntimeUpdate.Reconnecting(error))
                if (failures >= 3) {
                    if (recoverFromEventFailure(generation)) failures = 0
                    else delay((500L * (1L shl (failures.coerceAtMost(4) - 1))).coerceAtMost(5_000L))
                } else {
                    delay((500L * (1L shl (failures - 1))).coerceAtMost(5_000L))
                }
            }
        }
        synchronized(stateLock) { eventJob = null }
    }

    private suspend fun recoverFromEventFailure(expectedGeneration: Long? = null): Boolean {
        if (!canRecover() || (expectedGeneration != null && !isConversationGeneration(expectedGeneration))) return false
        updatesMutable.emit(PiRuntimeUpdate.Reconnecting(IllegalStateException("Pi runtime reconnecting")))
        val result = runCatching { connectCurrent(true, expectedGeneration) }
        val ready = result.getOrNull()
        if (ready != null) {
            return publishReady(ready.copy(reconnecting = true), expectedGeneration)
        }
        // Keep the runtime marked alive while its Activity-owned loop retries;
        // a transient reconnect must not make the UI submit a second start for
        // the same endpoint.
        if (result.exceptionOrNull() != null && (expectedGeneration == null || isConversationGeneration(expectedGeneration))) {
            updatesMutable.emit(PiRuntimeUpdate.Reconnecting(result.exceptionOrNull()!!))
        }
        return false
    }

    private fun currentRecord(): PiSessionRecord = synchronized(stateLock) { record }
    private fun isConversationGeneration(expected: Long): Boolean = synchronized(stateLock) {
        !closed && conversationGeneration == expected
    }
    private fun canRecover(): Boolean = synchronized(stateLock) { !closed && recoveryEnabled }
    private fun isClosed(): Boolean = synchronized(stateLock) { closed }

    private fun sameCwd(configured: String, running: String): Boolean =
        configured.trim().isNotBlank() && running.trim().isNotBlank() && configured.trim() == running.trim()

    private class RuntimeUnavailable : IllegalStateException("Pi runtime is not running")
}

/** Activity-owned registry; Compose only receives stable per-session runtimes. */
internal class PiSessionRuntimeManager(context: Context) {
    private val appContext = context.applicationContext
    private val managerJob = SupervisorJob()
    private val runtimes = LinkedHashMap<String, PiSessionRuntime>()

    @Synchronized
    fun activate(record: PiSessionRecord): PiSessionRuntime = runtime(record)

    @Synchronized
    fun runtime(record: PiSessionRecord): PiSessionRuntime {
        val existing = runtimes[record.id]
        if (existing != null) {
            existing.update(record)
            return existing
        }
        // Always use the persisted endpoint tuple. The old default bridge used an
        // implicit token and was the source of default-session reconnect failures
        // after registry migration.
        val bridge = PiBridge(appContext, record.port, record.token, record.id)
        return PiSessionRuntime(record, managerJob, bridge).also { runtimes[record.id] = it }
    }

    @Synchronized
    fun remove(record: PiSessionRecord): Job {
        val runtime = runtimes.remove(record.id) ?: runtime(record).also { runtimes.remove(record.id) }
        return runtime.closeAndCleanup(record.ownedSessionFile)
    }
}
