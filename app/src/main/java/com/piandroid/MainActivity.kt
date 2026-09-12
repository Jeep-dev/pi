package com.piandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import java.io.File

class MainActivity : ComponentActivity() {
    private val permission = "com.termux.permission.RUN_COMMAND"
    private val permissionRequestCode = 7001
    // Runtime ownership belongs to the Activity process, never to a Composable.
    private val runtimeManager by lazy { PiSessionRuntimeManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
        requestTermuxPermissionIfNeeded()
        setContent { PiTouchApp(runtimeManager) }
    }

    override fun onDestroy() {
        runtimeManager.close()
        super.onDestroy()
    }

    private fun requestTermuxPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val requested = mutableListOf<String>()
        val installed = runCatching { packageManager.getPackageInfo("com.termux", 0) }.isSuccess
        if (installed && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requested += permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requested += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (requested.isNotEmpty()) requestPermissions(requested.toTypedArray(), permissionRequestCode)
    }
}

private const val SESSION_SELECTION_TAG = "PiSessionSelection"
private const val ANDROID_EXTENSIONS_WIDGET = "__android_loaded_extensions"
private const val ANDROID_RESOURCES_WIDGET = "__android_loaded_resources"
private const val ANDROID_RESOURCES_COMMAND = "__android_loaded_resources"

private enum class Panel { Chat, Models, Thinking, Bash, Files, Diff, Stats, Settings, Themes }
internal data class LoadedResourceSection(val title: String, val items: List<String>)

internal fun parseLoadedResourceWidget(lines: List<String>): List<LoadedResourceSection> {
    val sections = mutableListOf<LoadedResourceSection>()
    var title: String? = null
    val rawItems = mutableListOf<String>()

    fun flush() {
        val currentTitle = title ?: return
        val items = rawItems
            .flatMap { it.removePrefix("  ").split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (items.isNotEmpty()) sections += LoadedResourceSection(currentTitle, items)
        rawItems.clear()
    }

    lines.forEach { rawLine ->
        val line = rawLine.trim()
        val header = if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
            line.substring(1, line.length - 1).trim()
        } else {
            ""
        }
        if (header.isNotBlank()) {
            flush()
            title = header
        } else if (title != null && line.isNotBlank()) {
            rawItems += line
        }
    }
    flush()
    return sections
}

private data class ChatLine(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val toolCallId: String = "",
    val contentIndex: Int = -1,
    val collapsed: Boolean = false,
    val delivery: String = "normal",
    val tokensBefore: Long = 0,
    val toolName: String = "",
    val toolArgs: String = "",
    val toolOutput: String = "",
    val toolMeta: String = "",
    val toolIsError: Boolean = false,
    val toolStartedAt: Long = 0L,
    val toolEndedAt: Long = 0L
)
private data class LocalCommand(val name: String, val description: String)

private val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.bg
private val HeaderBg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.headerBg
private val PanelBg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.panelBg
private val CardBg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.cardBg
private val ToolBg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.toolBg
private val UserBg: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.userBg
private val Border: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.border
private val Accent: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.accent
private val Blue: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.blue
private val TextMain: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.textMain
private val TextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.textMuted
private val ThinkingText: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.thinkingText
private val Danger: Color
    @Composable @ReadOnlyComposable get() = LocalPiColors.current.danger

private fun partialJsonString(raw: String, key: String): String? {
    val marker = "\"$key\""
    val keyIndex = raw.indexOf(marker)
    if (keyIndex < 0) return null
    val colon = raw.indexOf(':', keyIndex + marker.length)
    val quote = if (colon >= 0) raw.indexOf('"', colon + 1) else -1
    if (quote < 0) return null
    return buildString {
        var index = quote + 1
        while (index < raw.length) {
            val char = raw[index++]
            if (char == '"') break
            if (char != '\\' || index >= raw.length) {
                append(char)
                continue
            }
            when (val escaped = raw[index++]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'b' -> append('\b')
                'f' -> append('\u000C')
                '"', '\\', '/' -> append(escaped)
                'u' -> {
                    if (index + 4 <= raw.length) {
                        raw.substring(index, index + 4).toIntOrNull(16)?.let { append(it.toChar()) }
                        index += 4
                    }
                }
                else -> append(escaped)
            }
        }
    }
}

private fun toolDraftPreview(raw: String, count: Int): String {
    val path = partialJsonString(raw, "path")
    val content = partialJsonString(raw, "content")
    val preview = when {
        content != null -> content
        raw.isNotBlank() -> raw
        else -> "等待参数数据…"
    }
    return buildString {
        if (!path.isNullOrBlank()) append("目标：$path\n")
        append("实时生成内容：\n")
        append(preview)
        append("\n\n已生成 ${compactCount(count.toLong())} 字符 · 正常运行")
    }
}

private fun markdownText(source: String, codeColor: Color) = buildAnnotatedString {
    val text = source
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("(?m)^```[^\\n]*$"), "")
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append("**")
                    index += 2
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append('`')
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}

private fun directDocumentPath(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    if (!DocumentsContract.isDocumentUri(context, uri)) return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    return when (uri.authority) {
        "com.android.externalstorage.documents" -> {
            val parts = documentId.split(':', limit = 2)
            if (parts.size != 2) null
            else {
                val root = if (parts[0].equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage", parts[0])
                }
                File(root, parts[1]).absolutePath
            }
        }
        "com.android.providers.downloads.documents" -> documentId.removePrefix("raw:").takeIf { documentId.startsWith("raw:") }
        else -> null
    }
}

private fun bridgeHealthDiagnostic(health: PiHealth): String = listOf(
    health.lastExit,
    health.stderr.trim().takeIf { it.isNotBlank() }?.let { "stderr: $it" },
    health.stdoutTail.trim().takeIf { it.isNotBlank() }?.let { "stdout: $it" }
).filterNotNull().joinToString("\n")

private suspend fun pollPiSession(context: Context, record: PiSessionRecord): PiSessionRecord {
    val bridge = PiBridge(context, record.port, record.token, record.androidSessionId)
    val health = bridge.health(timeoutMs = 2_500).getOrNull()
        ?: return record.copy(
            status = if (record.lastError.isBlank()) PiSessionStatus.NOT_STARTED else PiSessionStatus.ERROR
        )
    if (!health.piRunning) {
        val diagnostic = bridgeHealthDiagnostic(health)
        return record.copy(
            cwd = health.cwd.ifBlank { record.cwd },
            status = if (diagnostic.isBlank() && record.lastError.isBlank()) PiSessionStatus.NOT_STARTED else PiSessionStatus.ERROR,
            lastError = diagnostic.ifBlank { record.lastError }
        )
    }
    val stateResult = bridge.state(timeoutMs = 4_000)
    val state = stateResult.getOrElse {
        val detail = listOf(it.message.orEmpty(), bridgeHealthDiagnostic(health)).filter { text -> text.isNotBlank() }.joinToString("\n")
        return record.copy(status = PiSessionStatus.ERROR, lastError = detail.ifBlank { "无法读取 Pi 状态" })
    }
    if (health.runtimeOwnerSessionId != record.androidSessionId || !runtimeConversationMatches(record, state)) {
        return record.copy(
            status = PiSessionStatus.ERROR,
            lastError = "Session ownership mismatch: android=${record.androidSessionId}, " +
                "runtime=${health.runtimeOwnerSessionId}, expectedPi=${record.piConversationId}, " +
                "actualPi=${state.piConversationId}"
        )
    }
    return record.copy(
        name = state.sessionName.ifBlank { record.name },
        cwd = health.cwd.ifBlank { record.cwd },
        status = if (state.streaming || state.compacting) PiSessionStatus.WORKING else PiSessionStatus.IDLE,
        lastActivity = record.lastActivity,
        lastError = ""
    )
}

@Composable
private fun PiTouchApp(runtimeManager: PiSessionRuntimeManager) {
    val context = LocalContext.current
    val sessionStore = remember { PiSessionStore(context) }
    val initialSessions = remember { sessionStore.loadOrCreateDefault() }
    remember(initialSessions) { runtimeManager.register(initialSessions) }
    var sessions by remember { mutableStateOf(initialSessions) }
    var activeAndroidSessionId by rememberSaveable {
        val saved = sessionStore.activeId()
        mutableStateOf(
            saved?.takeIf { id -> initialSessions.any { it.androidSessionId == id } }
                ?: initialSessions.firstOrNull()?.androidSessionId.orEmpty()
        )
    }
    var createSessionOpen by remember { mutableStateOf(false) }
    var createSessionName by remember { mutableStateOf("") }
    var createSessionCwd by remember { mutableStateOf(initialSessions.firstOrNull()?.cwd ?: PiSessionStore.DEFAULT_CWD) }
    var createSessionStartupArguments by remember { mutableStateOf("") }
    var emptySessionDrawerOpen by remember {
        mutableStateOf(emptySessionDrawerInitiallyOpen(initialSessions))
    }
    var managedSession by remember { mutableStateOf<PiSessionRecord?>(null) }
    var renameSession by remember { mutableStateOf<PiSessionRecord?>(null) }
    var renameSessionText by remember { mutableStateOf("") }
    var deleteSession by remember { mutableStateOf<PiSessionRecord?>(null) }
    val latestSessions by rememberUpdatedState(sessions)
    val latestActiveId by rememberUpdatedState(activeAndroidSessionId)

    fun updateSession(id: String, transform: (PiSessionRecord) -> PiSessionRecord) {
        val updated = orderPiSessions(sessions.map { if (it.androidSessionId == id) transform(it) else it })
        sessions = updated
        updated.firstOrNull { it.androidSessionId == id }?.let { runtimeManager.runtime(it).update(it) }
        sessionStore.save(updated, activeAndroidSessionId)
    }

    fun selectSession(id: String): Boolean {
        val before = activeAndroidSessionId
        Log.d(SESSION_SELECTION_TAG, "SESSION_CLICK target=$id ACTIVE_BEFORE=$before")
        val selectedId = selectPiSessionId(sessions, id)
        if (selectedId == null) {
            Log.w(SESSION_SELECTION_TAG, "SESSION_CLICK target=$id ACTIVE_BEFORE=$before ACTIVE_AFTER=$before RUNTIME_SESSION=")
            return false
        }
        activeAndroidSessionId = selectedId
        val targetRecord = sessions.first { it.androidSessionId == selectedId }
        val activeRuntime = runtimeManager.activate(targetRecord)
        sessionStore.save(orderPiSessions(sessions), selectedId)
        val runtimeOwnerSessionId = activeRuntime.runtimeOwnerSessionId
        val accepted = activeAndroidSessionId == selectedId &&
            runtimeManager.activeAndroidSessionId == selectedId &&
            runtimeOwnerSessionId == selectedId
        Log.d(
            SESSION_SELECTION_TAG,
            "SESSION_CLICK target=$id ACTIVE_BEFORE=$before ACTIVE_AFTER=$activeAndroidSessionId " +
                "RUNTIME_OWNER_SESSION=$runtimeOwnerSessionId PI_CONVERSATION=${activeRuntime.identitySnapshot().piConversationId}"
        )
        return accepted
    }

    fun canBindConversation(androidSessionId: String, sessionFile: String): Boolean =
        runtimeManager.canBindConversation(androidSessionId, sessionFile)

    fun requestSessionManagement(record: PiSessionRecord) {
        managedSession = record
    }

    fun confirmDeleteSession(record: PiSessionRecord) {
        val deletion = deletePiSessionState(sessions, record.androidSessionId, activeAndroidSessionId)
        sessions = deletion.remaining
        activeAndroidSessionId = deletion.activeId.orEmpty()
        emptySessionDrawerOpen = deletion.keepDrawerOpen
        sessionStore.save(deletion.remaining, deletion.activeId)
        runtimeManager.remove(record)
        managedSession = null
        deleteSession = null
    }

    fun createSession() {
        emptySessionDrawerOpen = false
        val record = sessionStore.create(
            name = createSessionName,
            cwd = createSessionCwd,
            launchCommand = sessions.firstOrNull {
                it.androidSessionId == activeAndroidSessionId
            }?.launchCommand.orEmpty(),
            records = sessions,
            startupArguments = createSessionStartupArguments
        )
        val updated = sessions + record
        sessions = updated
        activeAndroidSessionId = record.androidSessionId
        runtimeManager.activate(record)
        sessionStore.save(updated, record.androidSessionId)
        createSessionOpen = false
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val snapshot = latestSessions
            val refreshed = snapshot.map { record ->
                async(Dispatchers.IO) { pollPiSession(context, record) }
            }.awaitAll()
            val current = latestSessions
            val merged = mergePolledSessions(snapshot, current, refreshed)
            if (merged != current) {
                sessions = merged
                sessionStore.save(merged, latestActiveId)
            }
            if (merged.any { it.status == PiSessionStatus.WORKING }) {
                AgentKeepAliveService.start(context)
            } else {
                AgentKeepAliveService.stop(context)
            }
            delay(3_000)
        }
    }

    val activeSession = sessions.firstOrNull {
        it.androidSessionId == activeAndroidSessionId
    } ?: sessions.firstOrNull()

    var themeKey by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("pi_ui", Context.MODE_PRIVATE).getString("theme", "dark") ?: "dark")
    }
    val themeMode = PiThemeMode.fromStorage(themeKey)
    val colors = colorsFor(themeMode)
    val scheme = if (themeMode == PiThemeMode.Light) {
        lightColorScheme(
            background = colors.bg,
            surface = colors.bg,
            primary = colors.blue,
            onBackground = colors.textMain,
            onSurface = colors.textMain
        )
    } else {
        darkColorScheme(
            background = colors.bg,
            surface = colors.bg,
            primary = colors.blue,
            onBackground = colors.textMain,
            onSurface = colors.textMain
        )
    }
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val lightBars = themeMode == PiThemeMode.Light
        window.statusBarColor = if (themeMode == PiThemeMode.Dark) AndroidColor.BLACK else colors.headerBg.toArgb()
        window.navigationBarColor = if (themeMode == PiThemeMode.Dark) AndroidColor.BLACK else colors.bg.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
    CompositionLocalProvider(LocalPiColors provides colors) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = colors.bg) {
                if (activeSession == null) {
                    EmptySessionHost(
                        initiallyOpen = emptySessionDrawerOpen,
                        onNew = {
                            emptySessionDrawerOpen = false
                            createSessionName = ""
                            createSessionCwd = PiSessionStore.DEFAULT_CWD
                            createSessionStartupArguments = ""
                            createSessionOpen = true
                        }
                    )
                } else {
                    // Render exactly one chat UI. Background Runtime objects remain
                    // Activity-owned, but an inactive Session must never keep a hidden
                    // Compose chat tree that can leak/replay another Session's UI state.
                    key(activeSession.androidSessionId) {
                        val runtime = runtimeManager.activate(activeSession)
                        PiScreen(
                            runtime = runtime,
                            session = activeSession,
                            sessions = sessions,
                            activeAndroidSessionId = activeAndroidSessionId,
                            autoStart = true,
                            hostModifier = Modifier.fillMaxSize(),
                            themeMode = themeMode,
                            onTheme = { selected ->
                                themeKey = selected.storageKey
                                context.getSharedPreferences("pi_ui", Context.MODE_PRIVATE)
                                    .edit().putString("theme", selected.storageKey).apply()
                            },
                            onSelectSession = ::selectSession,
                            onNewSession = {
                                createSessionName = ""
                                createSessionCwd = activeSession.cwd
                                createSessionStartupArguments = ""
                                createSessionOpen = true
                            },
                            onSessionUpdate = ::updateSession,
                            onCanBindConversation = ::canBindConversation,
                            onManageSession = ::requestSessionManagement
                        )
                    }
                }
            }
            if (createSessionOpen) {
                SessionCreateDialog(
                    name = createSessionName,
                    cwd = createSessionCwd,
                    startupArguments = createSessionStartupArguments,
                    onName = { createSessionName = it },
                    onCwd = { createSessionCwd = it },
                    onStartupArguments = { createSessionStartupArguments = it },
                    onCreate = ::createSession,
                    onDismiss = { createSessionOpen = false }
                )
            }
            managedSession?.let { target ->
                SessionManageDialog(
                    record = target,
                    onDismiss = { managedSession = null },
                    onRename = {
                        renameSession = target
                        renameSessionText = sessionDisplayName(target)
                        managedSession = null
                    },
                    onTogglePinned = {
                        updateSession(target.androidSessionId) { it.copy(pinned = !it.pinned) }
                        managedSession = null
                    },
                    onDelete = {
                        deleteSession = target
                        managedSession = null
                    }
                )
            }
            renameSession?.let { target ->
                SessionRenameDialog(
                    currentName = renameSessionText,
                    onName = { renameSessionText = it },
                    onDismiss = { renameSession = null },
                    onConfirm = {
                        val updated = renamePiSession(sessions, target.androidSessionId, renameSessionText)
                        sessions = orderPiSessions(updated)
                        sessionStore.save(sessions, activeAndroidSessionId)
                        renameSession = null
                    }
                )
            }
            deleteSession?.let { target ->
                SessionDeleteDialog(
                    record = target,
                    onDismiss = { deleteSession = null },
                    onConfirm = { confirmDeleteSession(target) }
                )
            }
        }
    }
}

@Composable
private fun PiScreen(
    runtime: PiSessionRuntime,
    session: PiSessionRecord,
    sessions: List<PiSessionRecord>,
    activeAndroidSessionId: String,
    autoStart: Boolean,
    hostModifier: Modifier = Modifier,
    themeMode: PiThemeMode,
    onTheme: (PiThemeMode) -> Unit,
    onSelectSession: (String) -> Boolean,
    onNewSession: () -> Unit,
    onSessionUpdate: (String, (PiSessionRecord) -> PiSessionRecord) -> Unit,
    onCanBindConversation: (String, String) -> Boolean,
    onManageSession: (PiSessionRecord) -> Unit
) {
    val bridge = runtime.bridge
    var cwd by rememberSaveable(session.androidSessionId) { mutableStateOf(session.cwd) }
    var launchCommand by rememberSaveable(session.androidSessionId) { mutableStateOf(session.launchCommand) }
    var startupArguments by rememberSaveable(session.androidSessionId) { mutableStateOf(session.startupArguments) }
    var input by rememberSaveable(session.androidSessionId) { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var intentionalQuit by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.Chat) }
    var currentState by remember { mutableStateOf<PiState?>(null) }
    var currentStats by remember { mutableStateOf<PiStats?>(null) }
    var models by remember { mutableStateOf<List<PiModel>>(emptyList()) }
    var remoteCommands by remember { mutableStateOf<List<PiCommand>>(emptyList()) }
    var loadedExtensions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedResourceSections by remember { mutableStateOf<List<LoadedResourceSection>>(emptyList()) }
    var categorizedResourcesReceived by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val toolDraftChars = remember { mutableMapOf<Int, Int>() }
    val toolDraftBuffers = remember { mutableMapOf<Int, StringBuilder>() }
    val toolDraftRefreshAt = remember { mutableMapOf<Int, Long>() }
    val toolOutputRefreshAt = remember { mutableMapOf<String, Long>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerFocusRequester = remember { FocusRequester() }
    var composerFocusRequest by remember { mutableLongStateOf(0L) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val chatListState = rememberLazyListState()
    var followOutput by remember { mutableStateOf(true) }
    var showScrollControls by remember { mutableStateOf(false) }
    // Highest bridge event sequence already rendered by this PiScreen.
    var lastAppliedEventSeq by remember { mutableLongStateOf(0L) }
    var steeringQueueSize by remember { mutableStateOf(0) }
    var followUpQueueSize by remember { mutableStateOf(0) }

    var bashInput by rememberSaveable { mutableStateOf("") }
    var bashOutput by remember { mutableStateOf("") }
    var bashRunning by remember { mutableStateOf(false) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var files by remember { mutableStateOf<List<PiFile>>(emptyList()) }
    var selectedFile by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }
    var fileLoading by remember { mutableStateOf(false) }
    var fileTruncated by remember { mutableStateOf(false) }
    var diffText by remember { mutableStateOf("") }
    var pendingUi by remember { mutableStateOf<PiUiRequest?>(null) }
    var dialogInput by remember { mutableStateOf("") }
    var resumeSessions by remember { mutableStateOf<List<PiSession>>(emptyList()) }
    var resumeOpen by remember { mutableStateOf(false) }
    var resumeFilter by remember { mutableStateOf("") }
    var modelInitialSearch by remember { mutableStateOf("") }
    var defaultModelKey by remember { mutableStateOf(bridge.defaultModelKey()) }
    val pendingAttachments = remember { mutableStateListOf<PiAttachment>() }
    var attachmentNotice by remember { mutableStateOf("") }

    fun updateSessionRecord(transform: (PiSessionRecord) -> PiSessionRecord) {
        onSessionUpdate(session.androidSessionId, transform)
    }

    LaunchedEffect(Unit) {
        delay(100)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(composerFocusRequest, panel) {
        if (composerFocusRequest > 0 && panel == Panel.Chat) {
            delay(50)
            composerFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        runtime.launchTask {
            if (uris.isEmpty()) return@launchTask
            attachmentNotice = "正在建立文件引用…"
            var failures = 0
            uris.forEachIndexed { index, uri ->
                attachmentNotice = "正在添加附件 ${index + 1}/${uris.size}…"
                val descriptor = withContext(Dispatchers.IO) {
                    var name = uri.lastPathSegment ?: "attachment"
                    var byteCount = -1L
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) byteCount = cursor.getLong(sizeIndex)
                        }
                    }
                    Triple(name, context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }, byteCount)
                }
                val (name, mimeType, byteCount) = descriptor
                val directPath = directDocumentPath(context, uri)
                val attachment = directPath
                    ?.let { bridge.referenceAttachment(it, name, mimeType, byteCount).getOrNull() }
                    ?: bridge.uploadAttachment(uri, name, mimeType, byteCount).getOrElse {
                        failures++
                        attachmentNotice = "附件 $name 添加失败：${it.message}"
                        null
                    }
                if (attachment != null && pendingAttachments.none { it.path == attachment.path }) {
                    pendingAttachments.add(attachment)
                }
            }
            attachmentNotice = when {
                failures > 0 -> "$failures 个附件添加失败"
                pendingAttachments.isEmpty() -> "没有读取到可用文件"
                else -> ""
            }
        }
    }

    val localCommands = remember {
        listOf(
            LocalCommand("help", "查看 Android Agent 命令总览"),
            LocalCommand("resume", "选择并继续以前的 session"),
            LocalCommand("model", "选择模型，支持 provider/model 参数"),
            LocalCommand("thinking", "设置 thinking level"),
            LocalCommand("scoped-models", "浏览可用模型并设置默认模型"),
            LocalCommand("new", "新建 session"),
            LocalCommand("name", "给当前 session 命名"),
            LocalCommand("session", "查看 session / token / cost"),
            LocalCommand("tree", "只显示用户消息的 session 分支树"),
            LocalCommand("fork", "从以前的用户消息创建 fork"),
            LocalCommand("clone", "克隆当前 active branch"),
            LocalCommand("compact", "压缩当前上下文，可带自定义指令"),
            LocalCommand("export", "导出当前分支为 HTML 或 JSONL"),
            LocalCommand("import", "导入并继续 JSONL session"),
            LocalCommand("share", "创建私密 Gist 分享当前分支"),
            LocalCommand("copy", "复制最后一条助手消息"),
            LocalCommand("trust", "保存项目目录信任设置"),
            LocalCommand("reload", "重新加载扩展、skills、prompts 和上下文"),
            LocalCommand("login", "查看安全的 Provider 登录方式"),
            LocalCommand("logout", "查看安全的 Provider 退出方式"),
            LocalCommand("hotkeys", "查看移动端手势与操作"),
            LocalCommand("changelog", "查看此 Android 版本更新内容"),
            LocalCommand("quit", "保存并停止当前 Pi 进程"),
            LocalCommand("themes", "切换暗色、亮色或灰色主题并持久化"),
            LocalCommand("settings", "连接与运行设置"),
            LocalCommand("run", "通过 Pi RPC 执行 bash（也支持 ! / !!）"),
            LocalCommand("files", "浏览及编辑当前项目文件"),
            LocalCommand("diff", "查看当前 Git diff"),
            LocalCommand("abort", "停止当前 Agent 操作")
        )
    }

    fun restoreHistory(history: List<PiHistoryMessage>, preservePending: Boolean = false) {
        val pending = if (preservePending) {
            // queued/failed entries are not durable Pi history yet; sent entries
            // are expected to be present once Pi acknowledged them.
            lines.filter { it.role == "user" && it.delivery in setOf("steering_queued", "steering_failed") }
        } else {
            emptyList()
        }
        lines.clear()
        history.forEach { message ->
            lines.add(
                ChatLine(
                    role = message.role,
                    text = message.text,
                    toolCallId = message.toolCallId,
                    collapsed = message.collapsed,
                    tokensBefore = message.tokensBefore
                )
            )
        }
        pending.forEach { lines.add(it) }
    }

    suspend fun loadHistory(preservePending: Boolean = false) {
        val conversationVersion = runtime.conversationVersion()
        bridge.history().onSuccess {
            if (runtime.isConversationVersion(conversationVersion)) restoreHistory(it, preservePending)
        }
    }

    suspend fun refreshMeta() = coroutineScope {
        val state = async { runtime.refreshState().getOrNull() }
        val stats = async { bridge.stats().getOrNull() }
        val availableModels = async { bridge.models().getOrNull() }
        val availableCommands = async { bridge.commands().getOrNull() }
        state.await()
        stats.await()?.let { currentStats = it }
        availableModels.await()?.let { models = it }
        availableCommands.await()?.let { remoteCommands = it }
    }

    fun requestLoadedResources(delayMillis: Long = 0L) {
        // This is an extension command, so older Pi runtimes simply do not
        // advertise it. Keep the legacy widget untouched in that case.
        if (remoteCommands.none { it.name == ANDROID_RESOURCES_COMMAND }) return
        runtime.launchTask {
            if (delayMillis > 0) delay(delayMillis)
            // The categorized widget is fire-and-forget; a transient failure
            // must not erase the last authoritative snapshot.
            bridge.command("/$ANDROID_RESOURCES_COMMAND")
        }
    }

    fun addSystem(text: String) {
        if (text.isNotBlank()) lines.add(ChatLine("system", text))
    }

    fun appendStream(role: String, delta: String) {
        if (delta.isEmpty()) return
        // A steering card may be appended while the assistant is streaming.
        // Find the active stream by role instead of assuming it is the last row.
        val index = lines.indexOfLast { it.role == role && it.streaming }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(text = line.text + delta)
        } else {
            lines.add(ChatLine(role, delta, streaming = true))
        }
    }

    fun startToolDraft(contentIndex: Int, toolCallId: String, toolName: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = 0
        toolDraftBuffers[contentIndex] = StringBuilder()
        lines.add(
            ChatLine(
                role = "tool-draft",
                text = "",
                streaming = true,
                toolCallId = toolCallId,
                contentIndex = contentIndex,
                collapsed = true,
                toolName = toolName
            )
        )
    }

    fun updateToolDraft(contentIndex: Int, delta: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = (toolDraftChars[contentIndex] ?: 0) + delta.length
        toolDraftBuffers.getOrPut(contentIndex) { StringBuilder() }.append(delta)
    }

    fun finishToolDraft(contentIndex: Int, toolName: String) {
        toolDraftChars.remove(contentIndex)
        toolDraftBuffers.remove(contentIndex)
        toolDraftRefreshAt.remove(contentIndex)
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(toolName = toolName.ifBlank { line.toolName }, streaming = true)
        }
    }

    fun startTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        val startedAt = android.os.SystemClock.uptimeMillis()
        val existingIndex = lines.indexOfLast {
            it.toolCallId == toolCallId && (it.role == "tool-draft" || it.role == "tool")
        }
        val next = ChatLine(
            role = "tool",
            text = "",
            streaming = true,
            toolCallId = toolCallId,
            collapsed = true,
            toolName = event.toolName,
            toolArgs = event.argsText,
            toolStartedAt = startedAt
        )
        if (existingIndex >= 0) {
            val existing = lines[existingIndex]
            lines[existingIndex] = next.copy(collapsed = existing.collapsed)
        } else {
            lines.add(next)
        }
    }

    fun updateTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        val refreshKey = toolCallId.ifBlank { "__active_tool__" }
        val now = android.os.SystemClock.uptimeMillis()
        val lastRefresh = toolOutputRefreshAt[refreshKey] ?: 0L
        if (now - lastRefresh < 50L) return
        toolOutputRefreshAt[refreshKey] = now
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0) {
            startTool(event)
            index = lines.indexOfLast {
                it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
            }
        }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(
                toolName = event.toolName.ifBlank { line.toolName },
                toolArgs = event.argsText.ifBlank { line.toolArgs },
                toolOutput = event.text.trimEnd().ifBlank { line.toolOutput },
                toolMeta = event.metaText.ifBlank { line.toolMeta }
            )
        }
    }

    fun finishTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        toolOutputRefreshAt.remove(toolCallId.ifBlank { "__active_tool__" })
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0 && toolCallId.isNotBlank()) {
            index = lines.indexOfLast { it.role == "tool" && it.toolCallId == toolCallId }
        }
        val endedAt = android.os.SystemClock.uptimeMillis()
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(
                streaming = false,
                toolName = event.toolName.ifBlank { line.toolName },
                toolOutput = event.text.trimEnd().ifBlank { line.toolOutput },
                toolMeta = event.metaText.ifBlank { line.toolMeta },
                toolIsError = event.isError,
                toolEndedAt = endedAt
            )
        } else {
            lines.add(
                ChatLine(
                    role = "tool",
                    text = "",
                    streaming = false,
                    toolCallId = toolCallId,
                    collapsed = true,
                    toolName = event.toolName,
                    toolOutput = event.text.trimEnd(),
                    toolMeta = event.metaText,
                    toolIsError = event.isError,
                    toolStartedAt = endedAt,
                    toolEndedAt = endedAt
                )
            )
        }
    }

    fun finalizeAssistant(text: String, stopReason: String, errorMessage: String) {
        if (text.isNotBlank()) {
            val index = lines.indexOfLast { it.role == "assistant" && it.streaming }
            if (index >= 0) {
                lines[index] = lines[index].copy(text = text, streaming = false)
            } else {
                // A normal visible answer always creates a streaming assistant row from
                // text_delta before message_end. A final-only message_end after history/
                // recovery has no current owner and used to append stale old answers.
                Log.w("PiChatEvents", "Dropping orphan assistant message_end")
            }
        }
        assistantCompletionNotice(stopReason, text, errorMessage)?.let(::addSystem)
    }

    fun toggleLine(index: Int) {
        val line = lines.getOrNull(index) ?: return
        if (line.role.startsWith("tool") || line.role == "compaction") {
            lines[index] = line.copy(collapsed = !line.collapsed)
        }
    }

    fun settleStreams() {
        for (i in lines.indices) {
            if (lines[i].streaming) lines[i] = lines[i].copy(streaming = false)
        }
    }

    fun markSteeringFailed(text: String) {
        val index = lines.indexOfLast { it.role == "user" && it.delivery == "steering_queued" && it.text == text }
        if (index >= 0) lines[index] = lines[index].copy(delivery = "steering_failed")
    }

    fun reconcileSteeringQueue(count: Int) {
        var remaining = count
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (line.delivery == "steering" || line.delivery == "steering_queued") {
                val next = if (remaining > 0) {
                    remaining--
                    "steering_queued"
                } else {
                    "steering_sent"
                }
                if (line.delivery != next) lines[i] = line.copy(delivery = next)
            }
        }
    }

    fun restoreQueuedPrompts(prompts: List<String>) {
        val existing = lines.count { it.delivery == "steering" || it.delivery == "steering_queued" }
        prompts.drop(existing).forEach { prompt ->
            lines.add(ChatLine("user", prompt, delivery = "steering_queued"))
        }
    }

    suspend fun applyEvent(event: PiEvent, recovering: Boolean = false) {
        // Recovery snapshots can overlap live event batches. Drop stale events so an old
        // message_end can never be appended after a newer user request.
        if (event.seq > 0L) {
            if (event.seq <= lastAppliedEventSeq) return
            lastAppliedEventSeq = event.seq
        }
        when (event.type) {
            "agent_start" -> {
                status = "Working"
                updateSessionRecord { it.copy(status = PiSessionStatus.WORKING, lastActivity = System.currentTimeMillis(), lastError = "") }
                AgentKeepAliveService.start(bridge.applicationContext())
            }
            "agent_end" -> Unit
            "queue_update" -> {
                if (recovering) restoreQueuedPrompts(event.steeringQueue)
                steeringQueueSize = event.steeringCount
                followUpQueueSize = event.followUpCount
                reconcileSteeringQueue(event.steeringCount)
            }
            "agent_settled" -> {
                settleStreams()
                steeringQueueSize = 0
                followUpQueueSize = 0
                currentState = currentState?.copy(streaming = false, compacting = false)
                reconcileSteeringQueue(0)
                status = "Ready"
                updateSessionRecord { it.copy(status = PiSessionStatus.IDLE, lastError = "") }
                refreshMeta()
            }
            "message_update" -> when (event.subtype) {
                "text_delta" -> appendStream("assistant", event.text)
                "thinking_delta" -> appendStream("thinking", event.text)
                "toolcall_start" -> startToolDraft(event.contentIndex, event.toolCallId, event.text)
                "toolcall_delta" -> updateToolDraft(event.contentIndex, event.text)
                "toolcall_end" -> finishToolDraft(event.contentIndex, event.text)
                else -> Unit
            }
            "message_end" -> if (event.subtype == "assistant") {
                finalizeAssistant(event.text, event.stopReason, event.errorMessage)
                if (event.stopReason == "aborted" || event.stopReason == "error") {
                    for (i in lines.indices) {
                        if (lines[i].role == "tool-draft" && lines[i].streaming) {
                            lines[i] = lines[i].copy(
                                text = "",
                                toolMeta = "Cancelled",
                                streaming = false
                            )
                        }
                    }
                    toolDraftChars.clear()
                    toolDraftBuffers.clear()
                    toolDraftRefreshAt.clear()
                    toolOutputRefreshAt.clear()
                }
            }
            "tool_execution_start" -> startTool(event)
            "tool_execution_update" -> updateTool(event)
            "tool_execution_end" -> finishTool(event)
            "stderr", "extension_error" -> addSystem(event.text)
            "process_exit" -> {
                settleStreams()
                currentState = currentState?.copy(streaming = false, compacting = false)
                addSystem(event.text)
                if (intentionalQuit) {
                    intentionalQuit = false
                    connected = false
                    status = "Disconnected"
                    updateSessionRecord { it.copy(status = PiSessionStatus.NOT_STARTED) }
                } else {
                    status = "RECONNECTING"
                    updateSessionRecord { it.copy(status = PiSessionStatus.ERROR, lastError = event.text) }
                }
            }
            "compaction_start" -> {
                status = "Compacting"
                AgentKeepAliveService.start(bridge.applicationContext())
            }
            "compaction_end" -> {
                // Manual compaction reloads after its RPC response. Automatic
                // compaction has no command callback, so refresh it here.
                if (event.stopReason == "success" && event.subtype != "manual") {
                    loadHistory(preservePending = true)
                }
                status = if (currentState?.streaming == true) "Working" else "Ready"
            }
            "extension_ui_request" -> {
                val req = event.uiRequest
                when (req?.method) {
                    "notify" -> {
                        if (req.message == "ANDROID_SESSION_SWITCHED") {
                            loadHistory()
                            refreshMeta()
                            requestLoadedResources()
                            status = "Ready"
                        } else if (req.message == "ANDROID_PI_QUIT") {
                            intentionalQuit = true
                            runtime.disableRecovery()
                            status = "Stopping"
                        } else if (req.message == "资源已重新加载") {
                            addSystem(req.message)
                            // session_start precedes resources_discover in Pi;
                            // query after the reload settles to include contributed resources.
                            requestLoadedResources(delayMillis = 250)
                        } else {
                            addSystem(req.message)
                        }
                    }
                    "setStatus" -> if (req.statusText.isNotBlank()) status = req.statusText
                    "setWidget" -> when (req.title) {
                        ANDROID_RESOURCES_WIDGET -> {
                            categorizedResourcesReceived = true
                            loadedResourceSections = parseLoadedResourceWidget(req.options)
                        }
                        ANDROID_EXTENSIONS_WIDGET -> {
                            loadedExtensions = req.options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                            // Old Pi/bridge versions only know the legacy widget.
                            // Do not let it overwrite a categorized snapshot.
                            if (!categorizedResourcesReceived) {
                                loadedResourceSections = if (loadedExtensions.isEmpty()) {
                                    emptyList()
                                } else {
                                    listOf(LoadedResourceSection("Extensions", loadedExtensions))
                                }
                            }
                        }
                    }
                    "set_editor_text" -> if (req.message.isNotBlank()) input = req.message
                    "select", "confirm", "input", "editor" -> {
                        pendingUi = req
                        dialogInput = req.prefill.ifBlank { "" }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun applyRuntimeState(state: PiState, runtimeCwd: String = "") {
        currentState = state
        if (runtimeCwd.isNotBlank()) cwd = runtimeCwd
        updateSessionRecord { record ->
            bindPiConversation(record, state, record.sessionFile).copy(
                cwd = runtimeCwd.ifBlank { record.cwd },
                name = state.sessionName.ifBlank { record.name },
                ownedSessionFile = record.ownedSessionFile.ifBlank { state.sessionFile },
                sessionDirectory = record.sessionDirectory.ifBlank {
                    PiSessionStore.sessionDirectory(record.androidSessionId)
                }
            )
        }
    }

    suspend fun applyRuntimeReady(ready: PiRuntimeReady) {
        val state = ready.state
        // A restarted Bridge begins a new sequence epoch from zero.
        if (ready.snapshot.latest < lastAppliedEventSeq) lastAppliedEventSeq = 0L
        applyRuntimeState(state, ready.runtimeCwd)
        status = "Restoring session"
        restoreHistory(ready.snapshot.history, preservePending = ready.reconnecting)
        ready.snapshot.events.forEach { applyEvent(it, recovering = true) }
        pendingUi = ready.snapshot.pendingUi.lastOrNull()
        ready.snapshot.pendingUi.lastOrNull()?.let { request ->
            dialogInput = request.prefill.ifBlank { "" }
        }
        ready.snapshot.editorText?.let { input = it }
        connected = true
        connecting = false
        status = when {
            currentState?.compacting == true -> "Compacting"
            currentState?.streaming == true -> "Working"
            else -> "Ready"
        }
        panel = Panel.Chat
        refreshMeta()
        requestLoadedResources()
        if (currentState?.streaming == true || currentState?.compacting == true) {
            AgentKeepAliveService.start(bridgeContext = bridge.applicationContext())
        } else {
            AgentKeepAliveService.stop(bridge.applicationContext())
        }
    }

    val connect: () -> Unit = connect@{
        if (connecting) return@connect
        connecting = true
        connected = false
        intentionalQuit = false
        status = "Checking running agent"
        runtime.ensureConnected(session, autoStart)
    }

    // The runtime owns this loop. Compose only renders its updates; removing or
    // resizing this screen can no longer cancel the RPC/event/reconnect jobs.
    LaunchedEffect(runtime) {
        runtime.updates.collect { update ->
            when (update) {
                is PiRuntimeUpdate.Ready -> applyRuntimeReady(update.value)
                is PiRuntimeUpdate.State -> applyRuntimeState(update.value)
                is PiRuntimeUpdate.Snapshot -> {
                    if (update.value.latest < lastAppliedEventSeq) lastAppliedEventSeq = 0L
                    restoreHistory(update.value.history, preservePending = true)
                    update.value.events.forEach { applyEvent(it, recovering = true) }
                    pendingUi = update.value.pendingUi.lastOrNull()
                    update.value.editorText?.let { input = it }
                    connected = true
                    connecting = false
                }
                is PiRuntimeUpdate.Events -> {
                    update.value.events.forEach { applyEvent(it) }
                }
                is PiRuntimeUpdate.Reconnecting -> {
                    status = if (currentState?.streaming == true) "WORKING" else "RECONNECTING"
                    connecting = true
                }
                is PiRuntimeUpdate.Unavailable -> {
                    connected = false
                    connecting = false
                    status = "Disconnected"
                    updateSessionRecord { it.copy(status = PiSessionStatus.NOT_STARTED) }
                }
                is PiRuntimeUpdate.Failed -> {
                    connected = false
                    connecting = false
                    status = "Connection failed"
                    updateSessionRecord { it.copy(status = PiSessionStatus.ERROR, lastError = update.error.message.orEmpty()) }
                    addSystem("连接失败：${update.error.message}")
                }
                PiRuntimeUpdate.Disconnected -> {
                    connected = false
                    connecting = false
                    status = "Disconnected"
                    updateSessionRecord { it.copy(status = PiSessionStatus.NOT_STARTED) }
                }
            }
        }
    }

    LaunchedEffect(runtime, autoStart, session.cwd, session.launchCommand, session.startupArguments, session.sessionFile) {
        runtime.ensureConnected(session, autoStart)
    }

    fun sendExtensionCommand(text: String) {
        runtime.launchTask {
            bridge.command(text).onFailure { addSystem("命令发送失败：${it.message}") }
        }
    }

    fun executeInput(raw: String, attachments: List<PiAttachment> = emptyList()) {
        val text = raw.trim()
        if (text.isBlank() && attachments.isEmpty()) return
        val firstToken = text.substringBefore(' ').lowercase()
        if (!connected && firstToken !in setOf("/settings", "/themes")) {
            addSystem("还没有连接 Pi。点顶部 Connect 或输入 /settings。")
            return
        }
        if (!runtime.canSubmitTask() && firstToken !in setOf("/settings", "/themes")) {
            addSystem("当前 Session 正在停止，新的操作已拦截")
            return
        }
        if (attachments.isNotEmpty()) {
            followOutput = true
            val steering = currentState?.streaming == true || status == "Working"
            if (steering) steeringQueueSize += 1
            val attachmentSummary = attachments.joinToString(", ") { "[附件: ${it.name}]" }
            lines.add(
                ChatLine(
                    "user",
                    listOf(text, attachmentSummary).filter { it.isNotBlank() }.joinToString("\n"),
                    delivery = if (steering) "steering_queued" else "normal"
                )
            )
            runtime.launchTask {
                val behavior = if (steering) "steer" else null
                bridge.prompt(text, behavior, attachments).fold(
                    onSuccess = { status = "Working" },
                    onFailure = {
                        if (steering) {
                            steeringQueueSize = (steeringQueueSize - 1).coerceAtLeast(0)
                            markSteeringFailed(listOf(text, attachmentSummary).filter { it.isNotBlank() }.joinToString("\n"))
                        }
                        addSystem("发送附件失败：${it.message}")
                    }
                )
            }
            return
        }
        if (text.startsWith("!")) {
            val excluded = text.startsWith("!!")
            val bashCommand = text.removePrefix(if (excluded) "!!" else "!").trim()
            if (bashCommand.isBlank()) return
            panel = Panel.Bash
            bashInput = bashCommand
            runtime.launchTask {
                bashRunning = true
                bashOutput = "$ ${if (excluded) "!" else ""}$bashCommand\n"
                bridge.bash(bashCommand, excluded).fold(
                    onSuccess = { bashOutput += it.output + "\n[exit ${it.exitCode}]${if (excluded) " · excluded from context" else ""}" },
                    onFailure = { bashOutput += "ERROR: ${it.message}" }
                )
                bashRunning = false
                refreshMeta()
            }
            return
        }

        val command = text.substringBefore(' ').lowercase()
        val args = text.substringAfter(' ', "").trim()
        when (command) {
            "/help" -> addSystem(
                """Pi Android 命令
                |会话：/new /resume [搜索] /name /tree [id] /fork [id] /clone /compact [指令]
                |模型：/model [provider/model] /thinking [level] /scoped-models
                |数据：/session /copy /export [file] /import <file.jsonl> /share
                |运行：!command（写入上下文） · !!command（不写入上下文） · /abort
                |资源：/reload /trust /files /diff /themes /settings
                |系统：/hotkeys /changelog /login /logout /quit""".trimMargin()
            )
            "/resume" -> runtime.launchTask {
                bridge.sessions().fold(
                    onSuccess = { sessions ->
                        resumeSessions = sessions
                        resumeFilter = args
                        resumeOpen = true
                    },
                    onFailure = { addSystem("/resume 失败：${it.message}") }
                )
            }
            "/tree", "/fork", "/name", "/export", "/import", "/share", "/trust", "/reload", "/login", "/logout", "/quit" -> sendExtensionCommand(text)
            "/copy" -> runtime.launchTask {
                bridge.lastAssistantText().fold(
                    onSuccess = { copied ->
                        if (copied.isBlank()) {
                            addSystem("还没有可复制的助手消息")
                        } else {
                            val clipboard = bridge.applicationContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Pi assistant message", copied))
                            addSystem("已复制最后一条助手消息")
                        }
                    },
                    onFailure = { addSystem("/copy 失败：${it.message}") }
                )
            }
            "/model" -> {
                if (args.isBlank()) {
                    modelInitialSearch = ""
                    panel = Panel.Models
                } else {
                    val needle = args.lowercase()
                    val exact = models.filter {
                        it.id.lowercase() == needle ||
                            "${it.provider}/${it.id}".lowercase() == needle ||
                            it.name.lowercase() == needle
                    }
                    if (exact.size == 1) runtime.launchTask {
                        bridge.setModel(exact.first()).fold(
                            onSuccess = { refreshMeta(); addSystem("模型已切换为 ${exact.first().provider}/${exact.first().id}") },
                            onFailure = { addSystem("切换模型失败：${it.message}") }
                        )
                    } else {
                        modelInitialSearch = args
                        panel = Panel.Models
                        if (exact.isEmpty()) addSystem("没有唯一精确匹配，已按“$args”筛选模型")
                    }
                }
            }
            "/scoped-models" -> {
                modelInitialSearch = ""
                panel = Panel.Models
                addSystem("移动端没有 Ctrl+P 循环；可在此切换当前模型或设置新会话默认模型")
            }
            "/thinking" -> {
                if (args.isBlank()) panel = Panel.Thinking
                else {
                    val levels = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
                    val level = args.lowercase()
                    if (level !in levels) addSystem("未知 thinking level：$args；可用：${levels.joinToString()}")
                    else runtime.launchTask {
                        bridge.setThinking(level).fold(
                            onSuccess = { refreshMeta(); addSystem("Thinking = $level") },
                            onFailure = { addSystem("Thinking 设置失败：${it.message}") }
                        )
                    }
                }
            }
            "/session" -> {
                panel = Panel.Stats
                runtime.launchTask { bridge.stats().onSuccess { currentStats = it }.onFailure { addSystem(it.message.orEmpty()) } }
            }
            "/hotkeys" -> addSystem(
                """移动端操作
                |• 从屏幕中间区域右滑：打开 Pi Session 侧栏；左边缘保留返回手势
                |• 输入 /：打开可搜索命令面板
                |• 工作中仍可直接发送：按 Pi 规则作为 steering message 排队
                |• 输入 /abort 才会中止当前 Agent
                |• 滑动离开底部：暂停跟随；回到底部自动恢复
                |• 右侧 ↑/↓：直接跳到消息顶部/底部
                |• 长按消息：选择并复制文本
                |• 工具卡片：默认紧凑折叠，Show all 展开完整参数与输出
                |• ! 执行 bash 并加入上下文；!! 执行但不加入上下文""".trimMargin()
            )
            "/changelog" -> addSystem(
                """Pi Android v5.19.17
                |• /settings 显示并可编辑每个 Session 的附加启动参数
                |• 按 Pi 原生 CLI 分类提示常用模型、工具、资源和提示词启动参数
                |• 丢弃恢复快照与实时事件的重复/过期事件，避免旧回答串到新消息后面
                |• 自动跟随监听完整可见内容；工具参数、输出和状态增长也会持续贴底
                |• 工具卡片改为接近原生 Pi 的中性终端布局，不再把整条命令染成绿色
                |• 长命令和长输出默认同时折叠；单行超长命令也限制视觉行数
                |• 工具执行时间移到底部，不再挤压命令正文宽度
                |• Show all 同时展开完整参数和完整输出，不丢失工具结果
                |• 重建 Android Session / Runtime owner / Pi conversation 三层状态模型
                |• 修复重复 legacy conversation 所有权与仅按 cwd 重连造成的串会话
                |• Runtime 独占 history/state 提交并隔离重建客户端与过期事件
                |• 修复 /resume 把 Pi conversation.id 错当 Android session.id 导致的身份错误
                |• /resume 显式携带 Android session.id，并阻止其他 runtime 认领切换
                |• 丢弃 /resume 期间过期的后台 poll，避免覆盖当前 conversation 绑定
                |• Session 选择全链路只使用 Android session.id，并记录切换诊断日志
                |• Session 条目先提交 activeSession，再关闭侧栏，修复点击无效
                |• /resume 同时发现旧版 cwd 历史和当前 Android Session 私有历史
                |• 选择旧版历史后重启仍保持绑定，不迁移或删除旧文件
                |• /resume 切换后同步 Activity runtime，恢复的历史不会被旧快照覆盖
                |• /resume 只切换 Pi conversation，不改变 Android Session 隔离身份
                |• 无 Session 启动时自动展开侧栏，删除最后一个后可立即新建
                |• 删除最后一个 Session 后保留打开的侧栏，可直接新建第一个 Session
                |• 新建 Session 支持独立启动参数，并在恢复时保留参数
                |• Session 长按支持重命名、置顶和确认删除
                |• Stop 会清空 Pi 队列并取消当前任务，阻止后续操作继续执行
                |• Session 侧栏改为从中间区域右滑触发，保留左边缘返回手势
                |• 多个 Pi Session 以独立 Termux RPC 进程并行运行
                |• 从中间区域右滑打开 Session 侧栏，切换不会停止后台任务
                |• Session 列表、cwd、端口和恢复文件持久保存
                |• 恢复 edit 工具的原生 diff 数据，默认折叠且可展开全文
                |• 重连快照按持久历史边界去重，并保留未完成输出、工具结果和 steering 队列
                |• 显示真实工具失败和模型错误；限制 Bridge 事件缓存内存
                |• /reload 使用新 runtime 发布扩展列表和完成通知
                |• 超过 2 MB 的文件只提供安全只读预览，避免截断覆盖
                |• /fork 明确区分当前、已压缩及其他分支，并在历史 Fork 前确认
                |• /fork 现在真实切换到独立 session，并将所选消息恢复到输入框
                |• session 选择器自动遮蔽常见 API key、token 与私钥预览
                |• /compact 完成后立即切换到实际压缩上下文，可展开查看完整摘要
                |• 压缩后的旧原文仍安全保留在 append-only session 文件中，但不再错误显示为当前上下文
                |• 掉线重连始终恢复断线前实际活跃的 session，不再回到启动时的旧会话
                |• 顶部按原版 Pi 风格显示当前实际加载的 [Extensions] 列表
                |• 合并流式滚动与工具更新，生成中使用稳定文本渲染，减少闪烁和掉帧
                |• 底栏工作状态固定为简洁的 WORKING / RECONNECTING
                |• 修复 /tree 对话框等待导致的 timeout，并自动定位最新当前消息
                |• 恢复旧 session 时严格保留该会话的模型与 thinking level
                |• 补齐原版 Pi 核心斜杠命令入口
                |• /tree 只显示用户消息分支点，不显示工具执行过程
                |• /export、/import、/share、/copy、/trust、/reload、/quit
                |• /model 与 /thinking 支持直接参数
                |• 支持原版 ! / !! bash 语义
                |• 通用文件附件使用路径引用；可访问文件不复制、不内嵌
                |• /themes 支持完整暗色、亮色与灰色主题并持久化
                |• 重启后恢复完整思考、工具调用、执行输出和未关闭的 /tree
                |• 自动重连活动 Session，修复恢复期间的事件竞态""".trimMargin()
            )
            "/run" -> {
                panel = Panel.Bash
                if (args.isNotBlank()) {
                    bashInput = args
                    runtime.launchTask {
                        bashRunning = true
                        bridge.bash(args).fold(
                            onSuccess = { bashOutput = "$ $args\n${it.output}\n[exit ${it.exitCode}]" },
                            onFailure = { bashOutput = "ERROR: ${it.message}" }
                        )
                        bashRunning = false
                        refreshMeta()
                    }
                }
            }
            "/files" -> {
                panel = Panel.Files
                runtime.launchTask { bridge.files(currentPath).onSuccess { files = it }.onFailure { addSystem(it.message.orEmpty()) } }
            }
            "/diff" -> {
                panel = Panel.Diff
                runtime.launchTask { bridge.diff().onSuccess { diffText = it.ifBlank { "没有未提交改动" } }.onFailure { diffText = "ERROR: ${it.message}" } }
            }
            "/new" -> runtime.launchTask {
                bridge.newSession().fold(
                    onSuccess = {
                        lines.clear()
                        bridge.applyDefaultModel(models).onFailure { addSystem(it.message.orEmpty()) }
                        addSystem("已使用默认模型创建新的 Pi session")
                        refreshMeta()
                        requestLoadedResources()
                    },
                    onFailure = { addSystem("/new 失败：${it.message}") }
                )
            }
            "/compact" -> runtime.launchTask {
                status = "Compacting"
                bridge.compact(args).fold(
                    onSuccess = {
                        loadHistory(preservePending = true)
                        addSystem("上下文压缩完成 · 点击 [compaction] 可查看摘要")
                        refreshMeta()
                        status = if (currentState?.streaming == true) "Working" else "Ready"
                    },
                    onFailure = {
                        status = if (currentState?.streaming == true) "Working" else "Ready"
                        addSystem("/compact 失败：${it.message}")
                    }
                )
            }
            "/clone" -> runtime.launchTask {
                bridge.cloneSession().fold(
                    onSuccess = {
                        addSystem("当前 active branch 已克隆")
                        refreshMeta()
                        loadHistory()
                        requestLoadedResources()
                    },
                    onFailure = { addSystem("/clone 失败：${it.message}") }
                )
            }
            "/abort" -> {
                status = "Stopping"
                val stop = runtime.stopCurrentAgent()
                scope.launch {
                    stop.await().fold(
                        onSuccess = { addSystem("已停止当前任务并清空队列") },
                        onFailure = { addSystem("取消失败：${it.message}") }
                    )
                }
            }
            "/themes" -> {
                val requested = when (args.lowercase()) {
                    "dark", "暗色" -> PiThemeMode.Dark
                    "light", "亮色" -> PiThemeMode.Light
                    "gray", "grey", "灰色" -> PiThemeMode.Gray
                    else -> null
                }
                if (args.isBlank()) panel = Panel.Themes
                else if (requested == null) addSystem("未知主题：$args；可用：dark / light / gray")
                else {
                    onTheme(requested)
                    addSystem("主题已切换为${requested.displayName}")
                }
            }
            "/settings" -> panel = Panel.Settings
            else -> {
                followOutput = true
                val steering = currentState?.streaming == true || status == "Working"
                if (steering) steeringQueueSize += 1
                lines.add(ChatLine("user", text, delivery = if (steering) "steering_queued" else "normal"))
                runtime.launchTask {
                    val behavior = if (steering) "steer" else null
                    bridge.prompt(text, behavior).fold(
                        onSuccess = { status = "Working" },
                        onFailure = {
                            if (steering) {
                                steeringQueueSize = (steeringQueueSize - 1).coerceAtLeast(0)
                                markSteeringFailed(text)
                            }
                            addSystem("发送失败：${it.message}")
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(chatListState) {
        snapshotFlow {
            // Track every field that can change visible chat height. Tool cards grow via
            // toolArgs/toolOutput/toolMeta even while line count and line.text stay fixed.
            val visibleRevision = lines.fold(1L) { acc, line ->
                var next = acc * 31L + line.role.hashCode()
                next = next * 31L + line.text.hashCode()
                next = next * 31L + line.toolArgs.hashCode()
                next = next * 31L + line.toolOutput.hashCode()
                next = next * 31L + line.toolMeta.hashCode()
                next = next * 31L + line.delivery.hashCode()
                next = next * 31L + if (line.collapsed) 1L else 0L
                next = next * 31L + if (line.streaming) 1L else 0L
                next = next * 31L + if (line.toolIsError) 1L else 0L
                next
            }
            Triple(lines.size, visibleRevision, followOutput)
        }
            .distinctUntilChanged()
            .conflate()
            .collect { (lineCount, _, shouldFollow) ->
                if (shouldFollow && lineCount > 0) {
                    delay(24)
                    if (followOutput && lines.isNotEmpty()) chatListState.scrollToRealBottom()
                }
            }
    }

    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && followOutput && lines.isNotEmpty()) {
            delay(80)
            chatListState.scrollToRealBottom()
        }
    }

    LaunchedEffect(followOutput) {
        if (followOutput) showScrollControls = false
    }

    if (autoStart) pendingUi?.let { request ->
        fun sendUiResponse(action: suspend () -> Result<Unit>) {
            pendingUi = null
            runtime.launchTask {
                action().onFailure {
                    pendingUi = request
                    addSystem("界面请求响应失败：${it.message}")
                }
            }
        }
        ExtensionDialog(
            request = request,
            input = dialogInput,
            onInput = { dialogInput = it },
            onSelect = { value -> sendUiResponse { bridge.extensionUiResponse(request.id, value = value) } },
            onConfirm = { value -> sendUiResponse { bridge.extensionUiResponse(request.id, confirmed = value) } },
            onSubmit = { sendUiResponse { bridge.extensionUiResponse(request.id, value = dialogInput) } },
            onDismiss = { sendUiResponse { bridge.extensionUiResponse(request.id, cancelled = true) } }
        )
    }

    if (autoStart && resumeOpen) {
        AlertDialog(
            onDismissRequest = { resumeOpen = false },
            title = { Text("/resume · 选择会话") },
            text = {
                val resumeTokens = resumeFilter.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                val visibleSessions = resumeSessions.filter { session ->
                    val searchable = "${session.title} ${session.path}".lowercase()
                    resumeTokens.all { it in searchable }
                }
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = resumeFilter,
                        onValueChange = { resumeFilter = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        placeholder = { Text("搜索名称、首条消息或 Session ID") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                    if (resumeSessions.isEmpty()) {
                        Text("当前工作目录没有可恢复的 Pi session。", color = TextMuted)
                    } else if (visibleSessions.isEmpty()) {
                        Text("没有匹配的 session。", color = TextMuted, modifier = Modifier.padding(vertical = 12.dp))
                    } else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(visibleSessions) { session ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !session.current) {
                                        if (!onCanBindConversation(runtime.runtimeOwnerSessionId, session.path)) {
                                            addSystem("该 Pi conversation 已由另一个 Android Session 使用")
                                        } else {
                                            resumeOpen = false
                                            runtime.launchTask {
                                                status = "Switching session"
                                                runtime.switchPiConversation(
                                                    runtime.runtimeOwnerSessionId,
                                                    session.piConversationId,
                                                    session.path
                                                ).fold(
                                                    onSuccess = { status = "Ready" },
                                                    onFailure = {
                                                        addSystem("切换 session 失败：${it.message}")
                                                        status = "Ready"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    (if (session.current) "✓ 当前 · " else "") + session.title,
                                    color = if (session.current) Accent else TextMain,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Text(
                                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(session.modified)),
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { resumeOpen = false }) { Text("取消") } }
        )
    }

    val busy = connected && (
        status == "Working" || status == "Compacting" || status == "Stopping" ||
            currentState?.streaming == true || currentState?.compacting == true
        )
    val chatStatus = when {
        status.startsWith("RECONNECTING") -> status
        status == "Working" || currentState?.streaming == true -> {
            val queued = buildList {
                if (steeringQueueSize > 0) add("steering ${steeringQueueSize} 条")
                if (followUpQueueSize > 0) add("follow-up ${followUpQueueSize} 条")
            }
            if (queued.isEmpty()) "WORKING" else "WORKING · ${queued.joinToString("，")} 已排队"
        }
        else -> status
    }

    var drawerProgress by remember { mutableFloatStateOf(0f) }
    val currentDrawerProgress = rememberUpdatedState(drawerProgress)
    fun settleDrawer(target: Float) {
        scope.launch {
            val animation = Animatable(drawerProgress)
            animation.animateTo(target.coerceIn(0f, 1f), tween(180)) { drawerProgress = value }
        }
    }

    BoxWithConstraints(
        hostModifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(Bg)
            .pointerInput(Unit) {
                val edgeExclusion = with(density) { 24.dp.toPx() }
                val contentTop = with(density) { 32.dp.toPx() }
                val touchSlop = with(density) { 18.dp.toPx() }
                val drawerWidthPx = size.width * 0.86f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startProgress = currentDrawerProgress.value
                    val chatArea = size.height * 0.86f
                    val inSessionGestureZone =
                        startProgress <= 0.01f && down.position.x > edgeExclusion && down.position.y in (contentTop..chatArea) ||
                            startProgress > 0.01f && down.position.x > edgeExclusion
                    val velocityTracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                    var dragging = false
                    if (drawerWidthPx <= 0f) return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging) {
                            if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) return@awaitEachGesture
                            if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                                if (!inSessionGestureZone) return@awaitEachGesture
                                dragging = true
                            }
                        }
                        if (dragging) {
                            change.consume()
                            drawerProgress = (startProgress + dx / drawerWidthPx).coerceIn(0f, 1f)
                        }
                        if (!change.pressed) {
                            if (dragging) {
                                val velocityX = velocityTracker.calculateVelocity().x
                                val target = when {
                                    velocityX > 900f -> 1f
                                    velocityX < -900f -> 0f
                                    drawerProgress >= 0.35f -> 1f
                                    else -> 0f
                                }
                                settleDrawer(target)
                            }
                            break
                        }
                    }
                }
            }
    ) {
        val drawerWidth = maxWidth * 0.86f
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        BackHandler(enabled = drawerProgress > 0.01f) { settleDrawer(0f) }
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (panel) {
                    Panel.Chat -> ChatPanel(
                        lines = lines,
                        listState = chatListState,
                        cwd = cwd,
                        model = currentState?.let { it.modelName.ifBlank { it.modelId } }.orEmpty(),
                        status = chatStatus,
                        resourceSections = loadedResourceSections,
                        connected = connected,
                        onConnect = connect,
                        onSettings = { panel = Panel.Settings },
                        onFollowChange = { followOutput = it },
                        onUserScrollActivity = { showScrollControls = true },
                        onToggleLine = ::toggleLine
                    )
                    Panel.Models -> ModelsPanel(models, currentState, modelInitialSearch, defaultModelKey, { panel = Panel.Chat }, { model ->
                        bridge.saveDefaultModel(model); defaultModelKey = "${model.provider}/${model.id}"; addSystem("新对话默认模型：$defaultModelKey")
                    }, { model -> runtime.launchTask { bridge.setModel(model).fold(onSuccess = { refreshMeta(); addSystem("模型已切换为 ${model.provider}/${model.id}") }, onFailure = { addSystem("切换模型失败：${it.message}") }) } }, { level -> runtime.launchTask { bridge.setThinking(level).fold(onSuccess = { refreshMeta(); addSystem("reasoning_effort = $level") }, onFailure = { addSystem("reasoning_effort 设置失败：${it.message}") }) } })
                    Panel.Thinking -> ThinkingPanel(currentState?.thinkingLevel.orEmpty(), { panel = Panel.Chat }) { level -> runtime.launchTask { bridge.setThinking(level).fold(onSuccess = { refreshMeta(); panel = Panel.Chat; addSystem("Thinking = $level") }, onFailure = { addSystem("Thinking 设置失败：${it.message}") }) } }
                    Panel.Bash -> BashPanel(bashInput, bashOutput, bashRunning, { bashInput = it }, { panel = Panel.Chat }, {
                        val command = bashInput.trim(); if (command.isNotBlank()) runtime.launchTask { bashRunning = true; bashOutput = "$ $command\n"; bridge.bash(command).fold(onSuccess = { bashOutput += it.output + "\n[exit ${it.exitCode}]" }, onFailure = { bashOutput += "ERROR: ${it.message}" }); bashRunning = false; refreshMeta() }
                    }, { val stop = runtime.stopCurrentAgent(); scope.launch { stop.await(); bashRunning = false } })
                    Panel.Files -> FilesPanel(currentPath, files, selectedFile, fileText, fileLoading, fileTruncated, { panel = Panel.Chat }, { item ->
                        if (item.type == "directory") { currentPath = item.path; selectedFile = ""; fileText = ""; fileLoading = false; fileTruncated = false; runtime.launchTask { bridge.files(currentPath).onSuccess { files = it } } }
                        else { val requested = item.path; selectedFile = requested; fileText = ""; fileLoading = true; fileTruncated = false; runtime.launchTask { bridge.file(requested).fold(onSuccess = { loaded -> if (selectedFile == requested) { fileText = loaded.content; fileTruncated = loaded.truncated; fileLoading = false } }, onFailure = { if (selectedFile == requested) { selectedFile = ""; fileText = ""; fileLoading = false }; addSystem(it.message.orEmpty()) }) } }
                    }, { currentPath = currentPath.substringBeforeLast('/', ""); selectedFile = ""; fileText = ""; fileLoading = false; fileTruncated = false; runtime.launchTask { bridge.files(currentPath).onSuccess { files = it } } }, { fileText = it }, {
                        if (fileTruncated) addSystem("文件超过 2 MB，只显示了只读预览；为防止数据丢失，不能从这里覆盖保存") else if (!fileLoading && selectedFile.isNotBlank()) runtime.launchTask { bridge.writeFile(selectedFile, fileText).fold(onSuccess = { addSystem("已保存 $selectedFile") }, onFailure = { addSystem("保存失败：${it.message}") }) }
                    })
                    Panel.Diff -> TextPanel("/diff", diffText) { panel = Panel.Chat }
                    Panel.Stats -> StatsPanel(currentStats, currentState) { panel = Panel.Chat }
                    Panel.Themes -> ThemesPanel(themeMode, { panel = Panel.Chat }, onTheme)
                    Panel.Settings -> SettingsPanel(
                        cwd = cwd,
                        launchCommand = launchCommand,
                        startupArguments = startupArguments,
                        connected = connected,
                        autoCompaction = currentState?.autoCompactionEnabled ?: true,
                        onCwd = { value ->
                            cwd = value
                            updateSessionRecord { record -> record.copy(cwd = value) }
                        },
                        onLaunch = { value ->
                            launchCommand = value
                            updateSessionRecord { record -> record.copy(launchCommand = value) }
                        },
                        onStartupArguments = { value ->
                            startupArguments = value
                            updateSessionRecord { record -> record.copy(startupArguments = value) }
                        },
                        onConnect = connect,
                        onAutoCompaction = { enabled ->
                            runtime.launchTask {
                                bridge.setAutoCompaction(enabled).fold(
                                    onSuccess = {
                                        refreshMeta()
                                        addSystem("自动压缩：${if (enabled) "开启" else "关闭"}")
                                    },
                                    onFailure = { addSystem("自动压缩设置失败：${it.message}") }
                                )
                            }
                        },
                        onBack = { panel = Panel.Chat }
                    )
                }

                if (panel == Panel.Chat && showScrollControls) {
                    Column(Modifier.align(Alignment.BottomEnd).offset(y = (-108).dp).padding(end = 4.dp, bottom = 6.dp).width(40.dp)) {
                        Box(Modifier.fillMaxWidth().height(36.dp).clickable { followOutput = false; showScrollControls = false; scope.launch { chatListState.scrollToItem(0) } }, contentAlignment = Alignment.Center) { Text("↑", color = LocalPiColors.current.scrollText, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        Box(Modifier.fillMaxWidth().height(36.dp).clickable { showScrollControls = false; followOutput = true; scope.launch { if (lines.isNotEmpty()) chatListState.scrollToRealBottom() } }, contentAlignment = Alignment.Center) { Text("↓", color = LocalPiColors.current.scrollText, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                if (panel == Panel.Chat && input.startsWith("/")) {
                    CommandPalette(input, localCommands, remoteCommands, Modifier.align(Alignment.BottomCenter)) { name, remote -> input = ""; if (remote || name == "import") input = "/$name " else { keyboardController?.hide(); focusManager.clearFocus(); executeInput("/$name") } }
                }
            }
            if (panel == Panel.Chat) {
                Composer(input, busy, pendingAttachments, attachmentNotice, composerFocusRequester, { input = it }, { attachmentNotice = ""; filePicker.launch(arrayOf("*/*")) }, { attachment -> pendingAttachments.remove(attachment) }, {
                    val value = input; if (value.trimStart().startsWith("/")) { keyboardController?.hide(); focusManager.clearFocus() }; val attachments = pendingAttachments.toList(); input = ""; pendingAttachments.clear(); attachmentNotice = ""; executeInput(value, attachments)
                })
            }
            Footer(currentState, currentStats, chatStatus) { panel = Panel.Chat; composerFocusRequest++ }
        }
        if (drawerProgress > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)).clickable { settleDrawer(0f) }.zIndex(1f))
        SessionDrawer(sessions, activeAndroidSessionId, Modifier.width(drawerWidth).fillMaxHeight().offset { IntOffset((-drawerWidthPx * (1f - drawerProgress)).roundToInt(), 0) }.zIndex(2f), {
            Log.d(SESSION_SELECTION_TAG, "DRAWER_ITEM_CLICK target=$it ACTIVE_BEFORE=$activeAndroidSessionId"); if (onSelectSession(it)) settleDrawer(0f)
        }, { settleDrawer(0f); onNewSession() }, onManageSession)
    }
}

private fun sessionStatusLabel(record: PiSessionRecord): String = when (record.status) {
    PiSessionStatus.WORKING -> "Working"
    PiSessionStatus.IDLE -> "Idle"
    PiSessionStatus.NOT_STARTED -> "未启动"
    PiSessionStatus.ERROR -> "Error"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawer(sessions: List<PiSessionRecord>, activeAndroidSessionId: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit, onNew: () -> Unit, onManage: (PiSessionRecord) -> Unit = {}) {
    Column(modifier.background(PanelBg).border(1.dp, Border).navigationBarsPadding().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pi Sessions", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("＋", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 20.sp) }
        }
        Text("中间区域右滑打开 · 左边缘保留返回", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(sessions, key = { it.androidSessionId }) { record ->
                val selected = record.androidSessionId == activeAndroidSessionId
                Column(Modifier.fillMaxWidth().background(if (selected) CardBg else Color.Transparent).combinedClickable(onClick = { onSelect(record.androidSessionId) }, onLongClick = { onManage(record) }).padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected) "◆" else if (record.pinned) "★" else "○", color = if (selected || record.pinned) Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(sessionDisplayName(record), color = if (selected) Accent else TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(record.cwd, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 19.dp, top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 19.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sessionStatusLabel(record), color = when (record.status) { PiSessionStatus.WORKING -> Blue; PiSessionStatus.ERROR -> Danger; PiSessionStatus.IDLE -> Accent; PiSessionStatus.NOT_STARTED -> TextMuted }, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        if (record.lastActivity > 0) Text(" · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(record.lastActivity)), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    if (record.status == PiSessionStatus.ERROR && record.lastError.isNotBlank()) Text(record.lastError, color = Danger, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 19.dp, top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionCreateDialog(name: String, cwd: String, startupArguments: String, onName: (String) -> Unit, onCwd: (String) -> Unit, onStartupArguments: (String) -> Unit, onCreate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建 Pi Session") }, text = { Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(name, onName, Modifier.fillMaxWidth().padding(bottom = 8.dp), label = { Text("名称（可选）") }, singleLine = true, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        OutlinedTextField(cwd, onCwd, Modifier.fillMaxWidth().padding(bottom = 8.dp), label = { Text("工作目录 cwd") }, singleLine = true, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        OutlinedTextField(startupArguments, onStartupArguments, Modifier.fillMaxWidth(), label = { Text("启动参数（可选）") }, placeholder = { Text("例如：--no-tools") }, singleLine = false, minLines = 1, maxLines = 3, isError = startupArgumentsError(startupArguments) != null, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        startupArgumentsError(startupArguments)?.let { Text(it, color = Danger, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
    } }, confirmButton = { TextButton(onClick = onCreate, enabled = cwd.trim().isNotBlank() && startupArgumentsError(startupArguments) == null) { Text("创建") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SessionManageDialog(record: PiSessionRecord, onDismiss: () -> Unit, onRename: () -> Unit, onTogglePinned: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(sessionDisplayName(record)) }, text = { Column(Modifier.fillMaxWidth()) { TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) { Text("重命名") }; TextButton(onClick = onTogglePinned, modifier = Modifier.fillMaxWidth()) { Text(if (record.pinned) "取消置顶" else "置顶") }; TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("删除", color = Danger) } } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SessionRenameDialog(currentName: String, onName: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名 Pi Session") }, text = { OutlinedTextField(currentName, onName, Modifier.fillMaxWidth(), singleLine = true, label = { Text("显示名称") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)) }, confirmButton = { TextButton(onClick = onConfirm) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SessionDeleteDialog(record: PiSessionRecord, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除 Pi Session？") }, text = { Text("将停止并清理“${sessionDisplayName(record)}”自己的 Pi、Bridge 和会话配置；不会删除项目文件或其他 Session。") }, confirmButton = { TextButton(onClick = onConfirm) { Text("确认删除", color = Danger) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun EmptySessionHost(initiallyOpen: Boolean = false, onNew: () -> Unit) {
    val density = LocalDensity.current; val scope = rememberCoroutineScope(); var drawerProgress by remember(initiallyOpen) { mutableFloatStateOf(if (initiallyOpen) 1f else 0f) }; val currentProgress = rememberUpdatedState(drawerProgress)
    fun settle(target: Float) { scope.launch { val animation = Animatable(drawerProgress); animation.animateTo(target.coerceIn(0f, 1f), tween(180)) { drawerProgress = value } } }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().background(Bg).pointerInput(Unit) {
        val edgeExclusion = with(density) { 24.dp.toPx() }; val contentTop = with(density) { 32.dp.toPx() }; val touchSlop = with(density) { 18.dp.toPx() }; val drawerWidthPx = size.width * 0.86f
        awaitEachGesture { val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); val startProgress = currentProgress.value; val chatArea = size.height * 0.86f; val inZone = startProgress <= 0.01f && down.position.x > edgeExclusion && down.position.y in (contentTop..chatArea) || startProgress > 0.01f && down.position.x > edgeExclusion; var dragging = false
            while (true) { val event = awaitPointerEvent(PointerEventPass.Initial); val change = event.changes.firstOrNull { it.id == down.id } ?: break; val dx = change.position.x - down.position.x; val dy = change.position.y - down.position.y; if (!dragging) { if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) return@awaitEachGesture; if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) { if (!inZone) return@awaitEachGesture; dragging = true } }; if (dragging) { change.consume(); drawerProgress = (startProgress + dx / drawerWidthPx).coerceIn(0f, 1f) }; if (!change.pressed) { if (dragging) settle(if (drawerProgress >= 0.35f) 1f else 0f); break } }
        }
    }) {
        val drawerWidth = maxWidth * 0.86f; val drawerWidthPx = with(density) { drawerWidth.toPx() }; BackHandler(enabled = drawerProgress > 0.01f) { settle(0f) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text("没有 Pi Session", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
        if (drawerProgress > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)).clickable { settle(0f) }.zIndex(1f))
        SessionDrawer(emptyList(), "", Modifier.width(drawerWidth).fillMaxHeight().offset { IntOffset((-drawerWidthPx * (1f - drawerProgress)).roundToInt(), 0) }.zIndex(2f), {}, { settle(0f); onNew() })
    }
}

@Composable
private fun TerminalHeader(cwd: String, model: String, status: String, connected: Boolean, onConnect: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(HeaderBg).border(1.dp, LocalPiColors.current.headerDivider).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("~/", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 16.sp); Text(cwd.substringAfterLast('/').ifBlank { "home" }, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 16.sp); Spacer(Modifier.width(8.dp)); Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = if (connected) onSettings else onConnect) { Text(if (connected) "⋮" else "Connect", color = if (connected) TextMuted else Accent) } }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text("Model: ${model.ifBlank { "—" }}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp); Text(status, color = if (status == "Ready") Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

private suspend fun LazyListState.scrollToRealBottom() {
    repeat(3) { withFrameNanos { }; val count = layoutInfo.totalItemsCount; if (count == 0) return; val target = count - 1; val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1; if (lastVisible < target - 1) { scrollToItem(target); withFrameNanos { } }; if (!canScrollForward) return; val moved = scrollBy(1_000_000f); if (kotlin.math.abs(moved) < 0.5f || !canScrollForward) return }
}

@Composable
private fun ChatPanel(lines: List<ChatLine>, listState: LazyListState, cwd: String, model: String, status: String, resourceSections: List<LoadedResourceSection>, connected: Boolean, onConnect: () -> Unit, onSettings: () -> Unit, onFollowChange: (Boolean) -> Unit, onUserScrollActivity: () -> Unit, onToggleLine: (Int) -> Unit) {
    fun isAtBottom(): Boolean = !listState.canScrollForward
    val userScrollLock = remember(listState) { object : NestedScrollConnection { override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput) { onFollowChange(false); onUserScrollActivity() }; return Offset.Zero }; override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput && isAtBottom()) onFollowChange(true); return Offset.Zero } } }
    LaunchedEffect(listState) { snapshotFlow { isAtBottom() }.distinctUntilChanged().collect { atBottom -> if (atBottom) onFollowChange(true) } }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp).nestedScroll(userScrollLock), contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "session-meta") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("~/", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Text(cwd.substringAfterLast('/').ifBlank { "home" }, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Spacer(Modifier.width(6.dp)); Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Spacer(Modifier.weight(1f)); Text(if (connected) "⋮" else if (status == "Disconnected" || status.endsWith("failed")) "Connect" else "Connecting…", color = if (connected) TextMuted else Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.clickable { if (connected) onSettings() else onConnect() }.padding(8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text("Model: ${model.ifBlank { "—" }}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp); Text(status, color = when { status == "Ready" -> Accent; status.startsWith("WORKING") || status.startsWith("RECONNECTING") -> Blue; else -> TextMuted }, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                resourceSections.forEach { section -> Text("[${section.title}]", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)); Text("  ${section.items.joinToString(", ")}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp) }
            }
        }
        if (lines.isEmpty()) item { Text("Pi Touch GUI\n输入 / 调出真实命令。", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.padding(6.dp)) }
        itemsIndexed(lines) { lineIndex, line ->
            val fullText = line.text.trimEnd(); val visibleText = fullText
            SelectionContainer {
                when (line.role) {
                    "user" -> if (line.delivery in setOf("steering", "steering_queued", "steering_sent", "steering_failed")) Column(Modifier.fillMaxWidth().background(UserBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 10.dp)) { Text(when (line.delivery) { "steering_sent" -> "↳ STEERING · 已送达"; "steering_failed" -> "↳ STEERING · 发送失败"; else -> "↳ STEERING · 排队中" }, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)); Text(visibleText, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp) } else Text(visibleText, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().background(UserBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 12.dp))
                    "assistant" -> if (line.streaming) Text(visibleText, color = LocalPiColors.current.markdownText, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 4.dp)) else PiMarkdown(visibleText, modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 4.dp))
                    "thinking" -> Text(visibleText, color = ThinkingText, fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 3.dp))
                    "compaction" -> Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 9.dp)) { Text("[compaction]", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(if (line.tokensBefore > 0) "已从 ${java.text.NumberFormat.getIntegerInstance().format(line.tokensBefore)} tokens 压缩" else "上下文已压缩", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)); if (!line.collapsed) PiMarkdown(fullText, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)); TextButton(onClick = { onToggleLine(lineIndex) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) { Text(if (line.collapsed) "展开摘要 ↓" else "收起摘要 ↑", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
                    "tool", "tool-draft" -> {
                        val colors = LocalPiColors.current
                        var toolNow by remember(line.toolCallId, line.toolStartedAt) { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
                        LaunchedEffect(line.streaming, line.toolStartedAt) { while (line.streaming && line.toolStartedAt > 0L) { toolNow = android.os.SystemClock.uptimeMillis(); delay(250) } }
                        val toolOutput = line.toolOutput.ifBlank { fullText }
                        val outputHint = toolHiddenHint(toolOutput)
                        val argsHint = toolArgsHiddenHint(line.toolArgs)
                        val renderedOutput = if (line.collapsed && outputHint.isNotBlank()) toolOutputPreview(toolOutput) else toolOutput
                        val renderedArgs = if (line.collapsed && argsHint.isNotBlank()) toolArgsPreview(line.toolArgs) else line.toolArgs
                        val duration = if (line.toolStartedAt > 0L) formatToolDuration((if (line.toolEndedAt > 0L) line.toolEndedAt else toolNow) - line.toolStartedAt) else ""
                        val name = line.toolName.ifBlank { "tool" }
                        val background = when { line.toolIsError -> colors.toolErrorBg; line.streaming -> colors.toolPendingBg; else -> colors.toolSuccessBg }
                        val expandable = argsHint.isNotBlank() || outputHint.isNotBlank()
                        Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(3.dp)).padding(horizontal = 9.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (name == "bash") "$ bash" else name, color = colors.toolTitle, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (line.toolIsError) Text("error", color = Danger, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                            }
                            if (renderedArgs.isNotBlank()) {
                                Text(renderedArgs, color = if (name == "bash") colors.toolTitle else colors.markdownCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, maxLines = if (line.collapsed) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                            }
                            if (line.toolMeta.isNotBlank()) Text(line.toolMeta, color = if (line.toolIsError) Danger else colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
                            if (renderedOutput.isNotBlank()) {
                                val outputLines = renderedOutput.lines()
                                val styledOutput = buildAnnotatedString { outputLines.forEachIndexed { index, outputLine -> val color = when { outputLine.startsWith("+") && !outputLine.startsWith("+++") -> colors.toolDiffAdded; outputLine.startsWith("-") && !outputLine.startsWith("---") -> colors.toolDiffRemoved; else -> colors.toolOutput }; pushStyle(SpanStyle(color = color)); append(outputLine); pop(); if (index != outputLines.lastIndex) append('\n') } }
                                Text(styledOutput, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, maxLines = if (line.collapsed) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
                            }
                            if (expandable) {
                                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onToggleLine(lineIndex) }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text(if (line.collapsed) "Show all" else "Collapse", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                                    Spacer(Modifier.weight(1f))
                                    if (duration.isNotBlank()) Text(duration, color = colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                                }
                            } else if (duration.isNotBlank()) {
                                Text(duration, color = colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                            }
                        }
                    }
                    else -> Text(visibleText, color = if (line.text.contains("失败") || line.text.contains("错误") || line.text.contains("ERROR")) Danger else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun CommandPalette(query: String, local: List<LocalCommand>, remote: List<PiCommand>, modifier: Modifier = Modifier, onPick: (String, Boolean) -> Unit) {
    val needle = query.removePrefix("/").trim().lowercase(); val localNames = local.map { it.name }.toSet(); val choices = buildList<Pair<LocalCommand, Boolean>> { local.filter { it.name.contains(needle) }.forEach { add(it to false) }; remote.filter { !it.name.startsWith("__") && it.name !in localNames && it.name.contains(needle, ignoreCase = true) }.forEach { add(LocalCommand(it.name, it.description.ifBlank { it.source }) to true) } }.take(64); if (choices.isEmpty()) return; val paletteState = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxWidth()) { val allowedHeight = minOf(360.dp, (maxHeight - 8.dp).coerceAtLeast(96.dp)); Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().heightIn(max = allowedHeight).background(PanelBg, RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)).padding(vertical = 3.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text("Pi Commands", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${choices.size} · 上下滑动", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }; LazyColumn(state = paletteState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 3.dp)) { items(choices) { choice -> val cmd = choice.first; val remoteChoice = choice.second; Row(Modifier.fillMaxWidth().clickable { onPick(cmd.name, remoteChoice) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("/${cmd.name}", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(112.dp)); Text(cmd.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f)) } } } } }
}

@Composable
private fun Composer(value: String, busy: Boolean, attachments: List<PiAttachment>, attachmentNotice: String, focusRequester: FocusRequester, onValue: (String) -> Unit, onAttach: () -> Unit, onRemoveAttachment: (PiAttachment) -> Unit, onPrimary: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(LocalPiColors.current.composerBg)) {
        if (attachments.isNotEmpty() || attachmentNotice.isNotBlank()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { attachments.forEach { attachment -> Text("${attachment.name}${if (attachment.byteCount >= 0) " · ${compactCount(attachment.byteCount)}B" else ""}  ×", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.background(CardBg, RoundedCornerShape(5.dp)).clickable { onRemoveAttachment(attachment) }.padding(horizontal = 7.dp, vertical = 5.dp)) }; if (attachmentNotice.isNotBlank()) Text(attachmentNotice, color = Danger, fontSize = 10.sp) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton(onClick = onAttach, modifier = Modifier.width(44.dp).height(52.dp), contentPadding = PaddingValues(0.dp), enabled = true, colors = ButtonDefaults.textButtonColors(contentColor = Blue, disabledContentColor = TextMuted)) { Text("＋", fontFamily = FontFamily.Monospace, fontSize = 22.sp) }
            OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 132.dp).focusRequester(focusRequester), textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp), placeholder = { Text("输入消息或 / 命令…", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1) }, singleLine = false, minLines = 1, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onPrimary() }), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent))
            TextButton(onClick = onPrimary, modifier = Modifier.width(52.dp).height(52.dp), contentPadding = PaddingValues(0.dp), enabled = busy || value.isNotBlank() || attachments.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = Blue, disabledContentColor = LocalPiColors.current.disabledAction)) { Text("↵", fontFamily = FontFamily.Monospace, fontSize = 20.sp) }
        }
    }
}

private fun compactCount(value: Long): String = when { value >= 1_000_000_000 -> "${(value / 1_000_000_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}B"; value >= 1_000_000 -> "${(value / 1_000_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}M"; value >= 1_000 -> "${(value / 1_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}k"; else -> value.toString() }

@Composable
private fun Footer(state: PiState?, stats: PiStats?, status: String, onFocusComposer: () -> Unit) {
    val compactStatus = when { status.startsWith("WORKING") -> "WORKING"; status.startsWith("RECONNECTING") -> "RECONNECTING"; else -> "" }
    val parts = if (stats == null) buildList { if (compactStatus.isNotBlank()) add(compactStatus); add("—/—") } else buildList { if (compactStatus.isNotBlank()) add(compactStatus); if (stats.inputTokens > 0) add("↑${compactCount(stats.inputTokens)}"); if (stats.outputTokens > 0) add("↓${compactCount(stats.outputTokens)}"); if (stats.cacheRead > 0) add("R${compactCount(stats.cacheRead)}"); if (stats.cacheWrite > 0) add("W${compactCount(stats.cacheWrite)}"); if ((stats.cacheRead > 0 || stats.cacheWrite > 0) && stats.latestCacheHitRate >= 0) add("CH${"%.1f".format(java.util.Locale.US, stats.latestCacheHitRate)}%"); val subscription = state?.provider == "openai-codex" || state?.provider == "kimi-coding" || state?.provider?.contains("copilot", ignoreCase = true) == true; if (stats.cost > 0 || subscription) add("\$${"%.3f".format(java.util.Locale.US, stats.cost)}${if (subscription) " (sub)" else ""}"); val context = if (stats.contextPercent >= 0 && stats.contextWindow > 0) "${"%.1f".format(java.util.Locale.US, stats.contextPercent)}%/${compactCount(stats.contextWindow)}" else "—/—"; add(context + if (state?.autoCompactionEnabled == true) " (auto)" else "") }
    val scroll = rememberScrollState(); Box(Modifier.fillMaxWidth().background(Bg).clickable(onClick = onFocusComposer).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp)) { Text(parts.joinToString(" "), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(scroll)) }
}

@Composable
private fun PanelHeader(title: String, onBack: () -> Unit) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, color = Blue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onBack) { Text("返回", color = TextMuted) } } }

@Composable
private fun ModelsPanel(models: List<PiModel>, state: PiState?, initialSearch: String, defaultModelKey: String, onBack: () -> Unit, onSetDefault: (PiModel) -> Unit, onPick: (PiModel) -> Unit, onEffort: (String) -> Unit) {
    val effortLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"); var search by remember(initialSearch) { mutableStateOf(initialSearch) }; val tokens = search.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }; val visibleModels = models.filter { model -> val searchable = "${model.provider} ${model.id} ${model.name}".lowercase(); tokens.all { it in searchable } }
    Column(Modifier.fillMaxSize()) { PanelHeader("/model", onBack); LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 14.dp)) { item { Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(6.dp)).padding(12.dp)) { Text("reasoning_effort", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("Pi thinking level → provider reasoning_effort", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp, bottom = 6.dp)); effortLevels.forEach { level -> val selected = state?.thinkingLevel == level; Row(Modifier.fillMaxWidth().clickable { onEffort(level) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Text(level, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(if (selected) "✓ 当前" else "选择", color = if (selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } } } }; item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("搜索 provider、模型名称或 ID") }, singleLine = true, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)); Text("Models · ${visibleModels.size}/${models.size}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }; items(visibleModels) { model -> val selected = state?.provider == model.provider && state.modelId == model.id; val isDefault = defaultModelKey == "${model.provider}/${model.id}"; Row(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(6.dp)).clickable { onPick(model) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(model.name.ifBlank { model.id }, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp); Text("${model.provider}/${model.id}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }; Column(horizontalAlignment = Alignment.End) { Text(if (selected) "✓ 当前" else "选择", color = if (selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp); TextButton(onClick = { onSetDefault(model) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text(if (isDefault) "★ 新对话默认" else "设为默认", color = if (isDefault) Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } } } } } }
}

@Composable
private fun ThinkingPanel(current: String, onBack: () -> Unit, onPick: (String) -> Unit) { val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"); Column(Modifier.fillMaxSize()) { PanelHeader("/thinking", onBack); levels.forEach { level -> Row(Modifier.fillMaxWidth().clickable { onPick(level) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Text(level, color = TextMain, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)); if (level == current) Text("✓", color = Accent) } } } }

@Composable
private fun BashPanel(input: String, output: String, running: Boolean, onInput: (String) -> Unit, onBack: () -> Unit, onRun: () -> Unit, onAbort: () -> Unit) { Column(Modifier.fillMaxSize()) { PanelHeader("/run · Pi RPC bash", onBack); Text(output.ifBlank { "命令通过 Pi 的 bash RPC 执行，并进入 Pi session 上下文。" }, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)); Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(input, onInput, Modifier.weight(1f), singleLine = true, label = { Text("$ command") }); if (running) Button(onClick = onAbort, colors = ButtonDefaults.buttonColors(containerColor = LocalPiColors.current.stopButtonBg)) { Text("停止") } else Button(onClick = onRun, enabled = input.isNotBlank()) { Text("执行") } } } }

@Composable
private fun FilesPanel(path: String, files: List<PiFile>, selectedFile: String, fileText: String, fileLoading: Boolean, fileTruncated: Boolean, onBack: () -> Unit, onOpen: (PiFile) -> Unit, onUp: () -> Unit, onText: (String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) { PanelHeader("/files · /$path", onBack); if (selectedFile.isBlank()) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onUp, enabled = path.isNotBlank()) { Text("↑ 上级") }; Text(path.ifBlank { "/" }, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }; LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) { items(files) { file -> Text((if (file.type == "directory") "[DIR]  " else "[FILE] ") + file.name, color = if (file.type == "directory") Blue else TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(vertical = 11.dp)) } } } else { Text(selectedFile, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)); if (fileTruncated) Text("文件超过 2 MB：当前为截断的只读预览，不会允许覆盖原文件。", color = Danger, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)); OutlinedTextField(fileText, onText, Modifier.weight(1f).fillMaxWidth().padding(10.dp), readOnly = fileLoading || fileTruncated, placeholder = { if (fileLoading) Text("加载中…") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMain)); Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onSave, enabled = !fileLoading && !fileTruncated) { Text("保存") }; TextButton(onClick = { onOpen(PiFile("..", "directory", path)) }) { Text("返回文件列表") } } } }
}

@Composable
private fun TextPanel(title: String, text: String, onBack: () -> Unit) { Column(Modifier.fillMaxSize()) { PanelHeader(title, onBack); Text(text.ifBlank { "加载中…" }, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) } }

@Composable
private fun StatsPanel(stats: PiStats?, state: PiState?, onBack: () -> Unit) { Column(Modifier.fillMaxSize()) { PanelHeader("/session", onBack); val rows = listOf("Session" to (state?.sessionName?.ifBlank { state.piConversationId }.orEmpty()), "File" to (stats?.sessionFile ?: state?.sessionFile.orEmpty()), "Messages" to (stats?.totalMessages?.toString() ?: "—"), "Input tokens" to (stats?.inputTokens?.toString() ?: "—"), "Output tokens" to (stats?.outputTokens?.toString() ?: "—"), "Cache read" to (stats?.cacheRead?.toString() ?: "—"), "Cost" to (stats?.let { "$${"%.4f".format(it.cost)}" } ?: "—"), "Context" to (stats?.takeIf { it.contextPercent >= 0 }?.let { "${it.contextTokens}/${it.contextWindow} (${"%.1f".format(it.contextPercent)}%)" } ?: "—")); rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) { Text(label, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(110.dp)); Text(value, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } } } }

@Composable
private fun ThemesPanel(selected: PiThemeMode, onBack: () -> Unit, onSelect: (PiThemeMode) -> Unit) { Column(Modifier.fillMaxSize().background(Bg)) { PanelHeader("Themes", onBack); Text("主题会立即应用并自动保存。暗色主题保持原有配色。", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)); Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { PiThemeMode.entries.forEach { mode -> val preview = colorsFor(mode); Row(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(8.dp)).border(1.dp, if (mode == selected) Accent else Border, RoundedCornerShape(8.dp)).clickable { onSelect(mode) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.width(58.dp).height(38.dp).background(preview.bg, RoundedCornerShape(5.dp)).border(1.dp, preview.border, RoundedCornerShape(5.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(12.dp).height(24.dp).background(preview.userBg, RoundedCornerShape(2.dp))); Box(Modifier.width(12.dp).height(24.dp).background(preview.toolBg, RoundedCornerShape(2.dp))); Box(Modifier.width(12.dp).height(24.dp).background(preview.blue, RoundedCornerShape(2.dp))) }; Column(Modifier.weight(1f)) { Text(mode.displayName, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(mode.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }; Text(if (mode == selected) "✓ 当前" else "选择", color = if (mode == selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } } } } }

@Composable
private fun SettingsPanel(
    cwd: String,
    launchCommand: String,
    startupArguments: String,
    connected: Boolean,
    autoCompaction: Boolean,
    onCwd: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onStartupArguments: (String) -> Unit,
    onConnect: () -> Unit,
    onAutoCompaction: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val startupError = startupArgumentsError(startupArguments)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelHeader("/settings", onBack)
        OutlinedTextField(cwd, onCwd, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), label = { Text("Pi 工作目录") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        OutlinedTextField(launchCommand, onLaunch, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), label = { Text("Pi RPC 基础启动命令") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        OutlinedTextField(
            startupArguments,
            onStartupArguments,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("附加启动参数") },
            placeholder = { Text("例如：--no-tools") },
            minLines = 1,
            maxLines = 4,
            isError = startupError != null,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        )
        startupError?.let { Text(it, color = Danger, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }
        Text(
            "模型：--provider / --model / --thinking / --models\n" +
                "工具：--tools / --exclude-tools / --no-builtin-tools / --no-tools\n" +
                "资源：-e / --no-extensions / --skill / --no-skills / --prompt-template / --no-prompt-templates / --no-context-files\n" +
                "提示：--system-prompt / --append-system-prompt\n" +
                "其他：--name / --verbose / --approve / --no-approve",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
        Text(
            "附加参数会在启动时追加到上面的基础命令；两个输入框都可以编辑。",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )
        Row(Modifier.fillMaxWidth().clickable(enabled = connected) { onAutoCompaction(!autoCompaction) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动上下文压缩", color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("接近模型上下文上限时自动生成 compaction summary", color = TextMuted, fontSize = 11.sp)
            }
            Text(if (autoCompaction) "ON" else "OFF", color = if (autoCompaction) Accent else TextMuted, fontFamily = FontFamily.Monospace)
        }
        Text(
            "默认直接使用 Termux 中的 Pi。--mode rpc、Android Session 身份和私有 session-dir 由 App 维持；其他 Pi 原生启动参数可在上方编辑。",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(14.dp)
        )
        Button(onClick = onConnect, enabled = startupError == null, modifier = Modifier.padding(14.dp)) { Text(if (connected) "重新连接" else "连接 Pi") }
    }
}

@Composable
private fun ExtensionDialog(request: PiUiRequest, input: String, onInput: (String) -> Unit, onSelect: (String) -> Unit, onConfirm: (Boolean) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    when (request.method) {
        "select" -> { val isSessionTree = request.title == "Session Tree"; var filter by remember(request.id) { mutableStateOf("") }; val tokens = filter.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }; val visibleOptions = if (tokens.isEmpty()) request.options else request.options.filter { option -> val searchable = option.lowercase(); tokens.all { it in searchable } }; val optionsState = rememberLazyListState(); LaunchedEffect(request.id, filter, visibleOptions.size) { if (isSessionTree && filter.isBlank()) { val currentIndex = visibleOptions.indexOfFirst { "◆" in it }; if (currentIndex >= 0) optionsState.scrollToItem(currentIndex) } }; AlertDialog(onDismissRequest = onDismiss, title = { Text(request.title.ifBlank { "选择" }) }, text = { Column(Modifier.fillMaxWidth()) { if (isSessionTree) OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(bottom = 6.dp), placeholder = { Text("搜索消息、标签或节点 ID") }, singleLine = true, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)); if (isSessionTree) Text("◆ 当前消息 · ● 当前分支 · 共 ${visibleOptions.size} 条", color = TextMuted, fontSize = 10.sp); LazyColumn(state = optionsState, modifier = Modifier.heightIn(max = if (isSessionTree) 500.dp else 460.dp)) { items(visibleOptions) { option -> Text(option, color = if (isSessionTree && ("◆" in option || "●" in option)) Accent else TextMain, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 9.dp)) } }; if (visibleOptions.isEmpty()) Text("没有匹配的节点", color = TextMuted, modifier = Modifier.padding(vertical = 12.dp)) } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }
        "confirm" -> AlertDialog(onDismissRequest = onDismiss, title = { Text(request.title.ifBlank { "确认" }) }, text = { Text(request.message) }, confirmButton = { TextButton(onClick = { onConfirm(true) }) { Text("确认") } }, dismissButton = { TextButton(onClick = { onConfirm(false) }) { Text("取消") } })
        "input", "editor" -> AlertDialog(onDismissRequest = onDismiss, title = { Text(request.title.ifBlank { "输入" }) }, text = { OutlinedTextField(input, onInput, Modifier.fillMaxWidth().heightIn(min = if (request.method == "editor") 180.dp else 56.dp), placeholder = { Text(request.placeholder) }, singleLine = request.method == "input") }, confirmButton = { TextButton(onClick = onSubmit) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    }
}
