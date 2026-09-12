package com.piandroid

import android.content.Context
import android.util.Log
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

private const val PI_SESSION_IDENTITY_TAG = "PiSessionIdentity"

/** A snapshot delivered to a UI client without making that client own the runtime. */
internal data class PiRuntimeReady(
    val state: PiState,
    val snapshot: PiRecoverySnapshot,
    val runtimeCwd: String = "",
    val reconnecting: Boolean = false
)

internal data class PiRuntimeIdentity(
    val androidSessionId: String,
    val runtimeOwnerSessionId: String,
    val piConversationId: String,
    val sessionFile: String,
    val history: List<PiHistoryMessage>
)

/** Bind the mutable Pi conversation while preserving Android Session identity. */
internal fun runtimeConversationMatches(record: PiSessionRecord, state: PiState): Boolean {
    val expectedFile = record.sessionFile
    return if (expectedFile.isNotBlank()) {
        samePiConversationFile(expectedFile, state.sessionFile) &&
            (record.piConversationId.isBlank() || record.piConversationId == state.piConversationId)
    } else {
        isPrivatePiSessionFile(record.androidSessionId, state.sessionFile) &&
            state.piConversationId == record.piConversationId.ifBlank { record.androidSessionId }
    }
}

internal fun bindPiConversation(
    record: PiSessionRecord,
    state: PiState,
    fallbackSessionFile: String
): PiSessionRecord {
    val selectedFile = state.sessionFile.ifBlank { fallbackSessionFile }
    return record.copy(
        sessionFile = selectedFile,
        piConversationId = state.piConversationId.ifBlank { record.piConversationId },
        legacySessionFile = selectedFile.isNotBlank() && !isPrivatePiSessionFile(record.androidSessionId, selectedFile),
        status = if (state.streaming || state.compacting) PiSessionStatus.WORKING else PiSessionStatus.IDLE,
        lastActivity = if (state.streaming || state.compacting) System.currentTimeMillis() else record.lastActivity,
        lastError = ""
    )
}

internal sealed class PiRuntimeUpdate {
    data class Ready(val value: PiRuntimeReady) : PiRuntimeUpdate()
    data class State(val value: PiState) : PiRuntimeUpdate()
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
    bridge: PiBridge,
    private val claimConversation: (String, String) -> Boolean = { _, _ -> true }
) {
    val runtimeOwnerSessionId: String = initialRecord.androidSessionId
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
        check(next.androidSessionId == runtimeOwnerSessionId) {
            "Runtime owner mismatch: owner=$runtimeOwnerSessionId record=${next.androidSessionId}"
        }
        synchronized(stateLock) {
            if (!closed) {
                // Once connected, only this Runtime may change its conversation.
                // Compose/poll records can update presentation/configuration but
                // cannot push an older conversation binding back into Runtime.
                record = lastState?.let { state ->
                    bindPiConversation(next, state, state.sessionFile)
                } ?: next
            }
        }
    }

    fun identitySnapshot(): PiRuntimeIdentity = synchronized(stateLock) {
        PiRuntimeIdentity(
            androidSessionId = record.androidSessionId,
            runtimeOwnerSessionId = runtimeOwnerSessionId,
            piConversationId = lastState?.piConversationId.orEmpty().ifBlank { record.piConversationId },
            sessionFile = lastState?.sessionFile.orEmpty().ifBlank { record.sessionFile },
            history = lastSnapshot?.history.orEmpty()
        )
    }

    fun conversationVersion(): Long = synchronized(stateLock) { conversationGeneration }

    fun isConversationVersion(expected: Long): Boolean = synchronized(stateLock) {
        !closed && conversationGeneration == expected
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
                    updatesMutable.tryEmit(PiRuntimeUpdate.Ready(PiRuntimeReady(state, snapshot)))
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

    /** Refresh Pi state through the same serialized conversation authority. */
    suspend fun refreshState(): Result<PiState> = runCatching {
        connectMutex.withLock {
            val refreshed = bridge.state().getOrThrow()
            val changed = synchronized(stateLock) {
                val previous = lastState
                previous != null && (
                    previous.piConversationId != refreshed.piConversationId ||
                        conversationFileKey(previous.sessionFile) != conversationFileKey(refreshed.sessionFile)
                    )
            }
            if (changed) {
                val generation = synchronized(stateLock) { ++conversationGeneration }
                val snapshot = bridge.recoverySnapshot().getOrThrow()
                val ready = PiRuntimeReady(
                    state = refreshed,
                    snapshot = snapshot,
                    runtimeCwd = currentRecord().cwd
                )
                check(commitReady(ready, generation)) { "Pi conversation changed while refreshing state" }
            } else {
                synchronized(stateLock) {
                    check(!closed) { "Pi Session runtime is closed" }
                    lastState = refreshed
                    record = bindPiConversation(record, refreshed, refreshed.sessionFile)
                    updatesMutable.tryEmit(PiRuntimeUpdate.State(refreshed))
                }
            }
            refreshed
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

    /** Switch only the conversation owned by the initiating Android Session. */
    suspend fun switchPiConversation(
        targetAndroidSessionId: String,
        targetPiConversationId: String,
        sessionPath: String
    ): Result<PiRuntimeReady> = runCatching {
        val currentPiConversationId = synchronized(stateLock) {
            lastState?.piConversationId.orEmpty().ifBlank { record.piConversationId }
        }
        Log.d(
            PI_SESSION_IDENTITY_TAG,
            "RESUME_IDENTITY targetAndroidSessionId=$targetAndroidSessionId " +
                "runtimeOwnerSessionId=$runtimeOwnerSessionId currentPiConversationId=$currentPiConversationId " +
                "targetPiConversationId=$targetPiConversationId"
        )
        check(runtimeOwnerSessionId == targetAndroidSessionId) { "Pi Session identity mismatch" }
        connectMutex.withLock {
            val generation = synchronized(stateLock) {
                check(!closed) { "Pi Session runtime is closed" }
                check(connected) { "Pi Session is not connected" }
                // Invalidate every in-flight event/history result for the old conversation.
                ++conversationGeneration
            }
            val switched = bridge.switchSession(
                targetAndroidSessionId,
                targetPiConversationId,
                sessionPath
            ).getOrThrow()
            check(switched.piConversationId == targetPiConversationId) {
                "Pi conversation identity mismatch: expected=$targetPiConversationId actual=${switched.piConversationId}"
            }
            val snapshot = bridge.recoverySnapshot().getOrThrow()
            val ready = PiRuntimeReady(
                state = switched,
                snapshot = snapshot,
                runtimeCwd = currentRecord().cwd,
                reconnecting = false
            )
            check(commitReady(ready, generation, sessionPath)) {
                "Pi conversation changed while resume was completing"
            }
            ready
        }
    }

    fun closeAndCleanup(ownedSessionFile: String): Job {
        val closedNow = fenceClient()
        if (!closedNow) return scope.launch { }
        return scope.launch {
            try {
                bridge.shutdownAndCleanup(ownedSessionFile)
            } finally {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    /** Release only this Android client; the owner-specific Bridge/Pi keeps running. */
    fun closeClient(): Boolean {
        val closedNow = fenceClient()
        if (closedNow) scope.coroutineContext[Job]?.cancel()
        return closedNow
    }

    private fun fenceClient(): Boolean = synchronized(stateLock) {
        if (closed) return@synchronized false
        closed = true
        connected = false
        recoveryEnabled = false
        autoStartRequested = false
        connectionJob?.cancel()
        eventJob?.cancel()
        taskGate.close()
        true
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
                if (publishReady(result.getOrThrow())) {
                    startEventLoop()
                } else {
                    updatesMutable.emit(
                        PiRuntimeUpdate.Failed(
                            IllegalStateException("Pi conversation is already owned by another Android Session")
                        )
                    )
                }
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
            val ownerMatches = runningHealth?.runtimeOwnerSessionId == runtimeOwnerSessionId
            val conversationMatches = runtimeConversationMatches(configuredRecord, attached)
            if (ownerMatches && sameCwd(configuredRecord.cwd, runningCwd) && conversationMatches) {
                val snapshot = bridge.recoverySnapshot().getOrThrow()
                return PiRuntimeReady(
                    state = attached,
                    snapshot = snapshot,
                    runtimeCwd = runningCwd
                )
            }
            Log.w(
                PI_SESSION_IDENTITY_TAG,
                "REJECT_ATTACH androidSessionId=${configuredRecord.androidSessionId} " +
                    "runtimeOwnerSessionId=$runtimeOwnerSessionId endpointOwner=${runningHealth?.runtimeOwnerSessionId.orEmpty()} " +
                    "expectedPiConversationId=${configuredRecord.piConversationId} actualPiConversationId=${attached.piConversationId} " +
                    "expectedSessionFile=${configuredRecord.sessionFile} actualSessionFile=${attached.sessionFile}"
            )
            if (!allowStart) throw RuntimeUnavailable()
        } else if (!allowStart) {
            throw RuntimeUnavailable()
        }

        val previous = attached ?: bridge.state(timeoutMs = 1_500).getOrNull()
        val previousFile = synchronized(stateLock) { lastState?.sessionFile?.takeIf { it.isNotBlank() } }
            ?: configuredRecord.sessionFile.takeIf { it.isNotBlank() }
        val preserveSession = previousFile != null

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
            PiSessionStore.sessionDirectory(configuredRecord.androidSessionId)
        }
        val launch = if (preserveSession && previousFile != null) {
            bridge.recoveryLaunchCommand(configuredLaunch, previousFile)
        } else {
            bridge.freshLaunchCommand(
                configuredLaunch,
                configuredRecord.piConversationId.ifBlank { configuredRecord.androidSessionId },
                sessionDirectory
            )
        }
        val started = bridge.start(configuredRecord.cwd.trim(), launch).getOrThrow()
        val runtimeHealth = bridge.health(timeoutMs = 8_000).getOrThrow()
        check(runtimeHealth.runtimeOwnerSessionId == runtimeOwnerSessionId) {
            "Bridge owner mismatch: expected=$runtimeOwnerSessionId actual=${runtimeHealth.runtimeOwnerSessionId}"
        }
        check(runtimeConversationMatches(configuredRecord, started)) {
            "Pi conversation mismatch after start: expected=${configuredRecord.piConversationId}/${configuredRecord.sessionFile} " +
                "actual=${started.piConversationId}/${started.sessionFile}"
        }
        val snapshot = bridge.recoverySnapshot().getOrThrow()
        return PiRuntimeReady(
            state = started,
            snapshot = snapshot,
            runtimeCwd = runtimeHealth.cwd,
            reconnecting = true
        )
    }

    internal fun commitReady(
        ready: PiRuntimeReady,
        expectedGeneration: Long? = null,
        fallbackSessionFile: String = ready.state.sessionFile
    ): Boolean {
        if (!claimConversation(runtimeOwnerSessionId, ready.state.sessionFile)) return false
        return synchronized(stateLock) {
        if (closed || (expectedGeneration != null && conversationGeneration != expectedGeneration)) return false
        connected = true
        lastState = ready.state
        lastSnapshot = ready.snapshot
        eventCursor = ready.snapshot.latest
        record = bindPiConversation(record, ready.state, fallbackSessionFile)
        // tryEmit while holding the generation lock gives Ready/Snapshot/Events
        // one total order. An old result can never be queued after a newer resume.
        updatesMutable.tryEmit(PiRuntimeUpdate.Ready(ready))
        }
    }

    private fun publishReady(ready: PiRuntimeReady, expectedGeneration: Long? = null): Boolean =
        commitReady(ready, expectedGeneration)

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
                        val committed = synchronized(stateLock) {
                            if (closed || conversationGeneration != generation) false
                            else {
                                eventCursor = value.latest
                                lastSnapshot = value
                                updatesMutable.tryEmit(PiRuntimeUpdate.Snapshot(value))
                            }
                        }
                        if (!committed) continue
                    } else {
                        failures++
                        updatesMutable.emit(PiRuntimeUpdate.Reconnecting(snapshot.exceptionOrNull()!!))
                        delay(500L)
                    }
                } else {
                    val committed = synchronized(stateLock) {
                        if (closed || conversationGeneration != generation) false
                        else {
                            eventCursor = batch.latest
                            updatesMutable.tryEmit(PiRuntimeUpdate.Events(batch))
                        }
                    }
                    if (!committed) continue
                    if (batch.events.any {
                            it.uiRequest?.method == "notify" && it.uiRequest.message == "ANDROID_PI_QUIT"
                        }) {
                        disableRecovery()
                    }
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
    private val conversationOwners = LinkedHashMap<String, String>()

    @Volatile
    var activeAndroidSessionId: String? = null
        private set

    @Synchronized
    fun activate(record: PiSessionRecord): PiSessionRuntime {
        val target = runtime(record)
        check(target.runtimeOwnerSessionId == record.androidSessionId) {
            "Runtime owner mismatch: target=${record.androidSessionId} owner=${target.runtimeOwnerSessionId}"
        }
        activeAndroidSessionId = record.androidSessionId
        return target
    }

    @Synchronized
    fun register(records: List<PiSessionRecord>) {
        records.forEach { record ->
            val file = conversationFileKey(record.sessionFile)
            if (file.isNotBlank()) {
                check(conversationOwners[file].let { it == null || it == record.androidSessionId }) {
                    "Pi conversation already has another Android owner: $file"
                }
                conversationOwners[file] = record.androidSessionId
            }
        }
    }

    @Synchronized
    fun canBindConversation(androidSessionId: String, sessionFile: String): Boolean {
        val owner = conversationOwners[conversationFileKey(sessionFile)]
        return owner == null || owner == androidSessionId
    }

    @Synchronized
    private fun claimConversation(androidSessionId: String, sessionFile: String): Boolean {
        if (!canBindConversation(androidSessionId, sessionFile)) return false
        conversationOwners.entries.removeAll { it.value == androidSessionId }
        val file = conversationFileKey(sessionFile)
        if (file.isNotBlank()) conversationOwners[file] = androidSessionId
        return true
    }

    @Synchronized
    fun runtime(record: PiSessionRecord): PiSessionRuntime {
        val existing = runtimes[record.androidSessionId]
        if (existing != null) {
            existing.update(record)
            return existing
        }
        // Always use the persisted endpoint tuple. The old default bridge used an
        // implicit token and was the source of default-session reconnect failures
        // after registry migration.
        claimConversation(record.androidSessionId, record.sessionFile)
        val bridge = PiBridge(appContext, record.port, record.token, record.androidSessionId)
        return PiSessionRuntime(record, managerJob, bridge, ::claimConversation)
            .also { runtimes[record.androidSessionId] = it }
    }

    @Synchronized
    fun activeIdentity(): PiRuntimeIdentity? =
        activeAndroidSessionId?.let { runtimes[it]?.identitySnapshot() }

    @Synchronized
    fun remove(record: PiSessionRecord): Job {
        val runtime = runtimes.remove(record.androidSessionId)
            ?: runtime(record).also { runtimes.remove(record.androidSessionId) }
        if (activeAndroidSessionId == record.androidSessionId) activeAndroidSessionId = null
        conversationOwners.entries.removeAll { it.value == record.androidSessionId }
        return runtime.closeAndCleanup(record.ownedSessionFile)
    }

    @Synchronized
    fun close() {
        runtimes.values.forEach { it.closeClient() }
        runtimes.clear()
        conversationOwners.clear()
        activeAndroidSessionId = null
        managerJob.cancel()
    }
}
