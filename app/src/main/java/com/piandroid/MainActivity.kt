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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val permission = "com.termux.permission.RUN_COMMAND"
    private val permissionRequestCode = 7001
    private val bridge by lazy { PiBridge(applicationContext) }

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
        setContent { PiTouchApp(bridge) }
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

private enum class Panel { Chat, Models, Thinking, Bash, Files, Diff, Stats, Settings, Themes }
private data class ChatLine(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val toolCallId: String = "",
    val contentIndex: Int = -1,
    val collapsed: Boolean = false
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

@Composable
private fun PiTouchApp(bridge: PiBridge) {
    val context = LocalContext.current
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
                PiScreen(
                    bridge = bridge,
                    themeMode = themeMode,
                    onTheme = { selected ->
                        themeKey = selected.storageKey
                        context.getSharedPreferences("pi_ui", Context.MODE_PRIVATE)
                            .edit().putString("theme", selected.storageKey).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun PiScreen(bridge: PiBridge, themeMode: PiThemeMode, onTheme: (PiThemeMode) -> Unit) {
    var cwd by rememberSaveable { mutableStateOf("/data/data/com.termux/files/home") }
    var launchCommand by rememberSaveable { mutableStateOf("pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts") }
    var input by rememberSaveable { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var panel by remember { mutableStateOf(Panel.Chat) }
    var currentState by remember { mutableStateOf<PiState?>(null) }
    var currentStats by remember { mutableStateOf<PiStats?>(null) }
    var models by remember { mutableStateOf<List<PiModel>>(emptyList()) }
    var remoteCommands by remember { mutableStateOf<List<PiCommand>>(emptyList()) }
    var cursor by remember { mutableLongStateOf(0L) }
    var eventFailures by remember { mutableStateOf(0) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val toolDraftChars = remember { mutableMapOf<Int, Int>() }
    val toolDraftBuffers = remember { mutableMapOf<Int, StringBuilder>() }
    val toolDraftRefreshAt = remember { mutableMapOf<Int, Long>() }
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

    var bashInput by rememberSaveable { mutableStateOf("") }
    var bashOutput by remember { mutableStateOf("") }
    var bashRunning by remember { mutableStateOf(false) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var files by remember { mutableStateOf<List<PiFile>>(emptyList()) }
    var selectedFile by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }
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
        scope.launch {
            if (uris.isEmpty()) return@launch
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
            LocalCommand("themes", "切换暗色、亮色或灰色主题"),
            LocalCommand("settings", "连接与运行设置"),
            LocalCommand("run", "通过 Pi RPC 执行 bash（也支持 ! / !!）"),
            LocalCommand("files", "浏览及编辑当前项目文件"),
            LocalCommand("diff", "查看当前 Git diff"),
            LocalCommand("abort", "停止当前 Agent 操作")
        )
    }

    fun restoreHistory(history: List<PiHistoryMessage>) {
        lines.clear()
        history.forEach { message ->
            lines.add(
                ChatLine(
                    role = message.role,
                    text = message.text,
                    toolCallId = message.toolCallId,
                    collapsed = message.collapsed
                )
            )
        }
    }

    suspend fun loadHistory() {
        bridge.history().onSuccess(::restoreHistory)
    }

    suspend fun refreshMeta() {
        bridge.state().onSuccess { currentState = it }
        bridge.stats().onSuccess { currentStats = it }
        bridge.models().onSuccess { models = it }
        bridge.commands().onSuccess { remoteCommands = it }
    }

    fun addSystem(text: String) {
        if (text.isNotBlank()) lines.add(ChatLine("system", text))
    }

    fun appendStream(role: String, delta: String) {
        if (delta.isEmpty()) return
        val last = lines.lastOrNull()
        if (last?.role == role && last.streaming) {
            lines[lines.lastIndex] = last.copy(text = last.text + delta)
        } else {
            lines.add(ChatLine(role, delta, streaming = true))
        }
    }

    fun startToolDraft(contentIndex: Int, toolCallId: String, text: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = 0
        toolDraftBuffers[contentIndex] = StringBuilder()
        lines.add(
            ChatLine(
                role = "tool-draft",
                text = "$text\n\n正在生成调用参数…",
                streaming = true,
                toolCallId = toolCallId,
                contentIndex = contentIndex,
                collapsed = true
            )
        )
    }

    fun updateToolDraft(contentIndex: Int, delta: String) {
        if (contentIndex < 0) return
        val previous = toolDraftChars[contentIndex] ?: 0
        val current = previous + delta.length
        toolDraftChars[contentIndex] = current
        val buffer = toolDraftBuffers.getOrPut(contentIndex) { StringBuilder() }.append(delta)
        val now = android.os.SystemClock.uptimeMillis()
        val lastRefresh = toolDraftRefreshAt[contentIndex] ?: 0L
        if (now - lastRefresh < 50) return
        toolDraftRefreshAt[contentIndex] = now
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(text = line.text.substringBefore("\n\n") + "\n\n" + toolDraftPreview(buffer.toString(), current))
        }
    }

    fun finishToolDraft(contentIndex: Int, text: String) {
        val count = toolDraftChars.remove(contentIndex) ?: 0
        val raw = toolDraftBuffers.remove(contentIndex)?.toString().orEmpty()
        toolDraftRefreshAt.remove(contentIndex)
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(text = "$text\n\n${toolDraftPreview(raw, count)}\n\n参数完整，等待执行…")
        }
    }

    fun startTool(toolCallId: String, text: String) {
        val existingIndex = lines.indexOfLast {
            it.toolCallId == toolCallId && (it.role == "tool-draft" || it.role == "tool")
        }
        if (existingIndex >= 0) {
            val existing = lines[existingIndex]
            lines[existingIndex] = ChatLine("tool", text, streaming = true, toolCallId = toolCallId, collapsed = existing.collapsed)
        } else {
            lines.add(ChatLine("tool", text, streaming = true, toolCallId = toolCallId, collapsed = true))
        }
    }

    fun updateTool(toolCallId: String, text: String) {
        if (text.isBlank()) return
        val index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index >= 0) {
            val line = lines[index]
            val header = line.text.substringBefore("\n\n工具输出：")
            lines[index] = line.copy(text = "$header\n\n工具输出：\n${text.trimEnd()}")
        } else {
            lines.add(ChatLine("tool", text.trimEnd(), streaming = true, toolCallId = toolCallId))
        }
    }

    fun finishTool(toolCallId: String, text: String) {
        val index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index >= 0) {
            val line = lines[index]
            val header = line.text.substringBefore("\n\n工具输出：").trimEnd()
            val suffix = if (text.isBlank()) "" else "\n\n${text.trim()}"
            lines[index] = line.copy(text = header + suffix, streaming = false, collapsed = true)
        } else if (text.isNotBlank()) {
            lines.add(ChatLine("tool", text.trim(), toolCallId = toolCallId))
        }
    }

    fun finalizeAssistant(text: String) {
        if (text.isBlank()) return
        val index = lines.indexOfLast { it.role == "assistant" && it.streaming }
        if (index >= 0) lines[index] = lines[index].copy(text = text, streaming = false)
        else if (lines.lastOrNull { it.role == "assistant" }?.text != text) lines.add(ChatLine("assistant", text))
    }

    fun toggleTool(toolCallId: String) {
        val index = lines.indexOfLast { it.role.startsWith("tool") && it.toolCallId == toolCallId }
        if (index >= 0) lines[index] = lines[index].copy(collapsed = !lines[index].collapsed)
    }

    fun settleStreams() {
        for (i in lines.indices) {
            if (lines[i].streaming) lines[i] = lines[i].copy(streaming = false)
        }
    }

    suspend fun applyEvent(event: PiEvent) {
        when (event.type) {
            "agent_start" -> status = "Working"
            "agent_end" -> Unit
            "agent_settled" -> {
                settleStreams()
                status = "Ready"
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
                finalizeAssistant(event.text)
                if (event.stopReason == "aborted" || event.stopReason == "error") {
                    for (i in lines.indices) {
                        if (lines[i].role == "tool-draft" && lines[i].streaming) {
                            lines[i] = lines[i].copy(
                                text = lines[i].text.substringBefore("\n\n") + "\n\n已取消，工具未执行",
                                streaming = false
                            )
                        }
                    }
                    toolDraftChars.clear()
                    toolDraftBuffers.clear()
                    toolDraftRefreshAt.clear()
                }
            }
            "tool_execution_start" -> startTool(event.toolCallId, event.text)
            "tool_execution_update" -> updateTool(event.toolCallId, event.text)
            "tool_execution_end" -> finishTool(event.toolCallId, event.text)
            "stderr", "extension_error" -> addSystem(event.text)
            "process_exit" -> {
                settleStreams()
                addSystem(event.text)
                status = "Disconnected"
                connected = false
                AgentKeepAliveService.stop(bridge.applicationContext())
            }
            "compaction_start" -> status = "Compacting"
            "compaction_end" -> status = "Ready"
            "extension_ui_request" -> {
                val req = event.uiRequest
                when (req?.method) {
                    "notify" -> {
                        if (req.message == "ANDROID_SESSION_SWITCHED") {
                            loadHistory()
                            refreshMeta()
                            status = "Ready"
                        } else {
                            addSystem(req.message)
                        }
                    }
                    "setStatus" -> if (req.statusText.isNotBlank()) status = req.statusText
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

    val connect: () -> Unit = connect@{
        if (connecting) return@connect
        connecting = true
        scope.launch {
            try {
                connected = false
                status = "Checking running agent"
                val attached = bridge.attachToRunningBridge().getOrNull()
                if (attached != null) {
                    currentState = attached
                    status = "Ready"
                } else {
                    status = "Checkpointing session"
                    bridge.state().onSuccess { previous ->
                        currentState = previous
                        if (!previous.streaming && !previous.compacting) {
                            val canCheckpoint = bridge.commands().getOrDefault(emptyList()).any { it.name == "__android_checkpoint" }
                            if (canCheckpoint) bridge.prompt("/__android_checkpoint")
                        }
                    }
                    status = "Installing bridge"
                    bridge.installAndStartBridge().getOrThrow()
                    status = "Waiting for bridge"
                    bridge.waitForBridge().getOrThrow()
                    status = "Starting Pi"
                    val recoveredLaunch = bridge.recoveryLaunchCommand(launchCommand.trim())
                    currentState = bridge.start(cwd.trim(), recoveredLaunch).getOrThrow()
                    val availableModels = bridge.models().getOrDefault(emptyList())
                    models = availableModels
                    bridge.applyDefaultModel(availableModels).onSuccess { selected ->
                        if (selected != null) bridge.state().onSuccess { state -> currentState = state }
                    }.onFailure { addSystem(it.message.orEmpty()) }
                }
                status = "Restoring session"
                val snapshot = bridge.recoverySnapshot().getOrThrow()
                restoreHistory(snapshot.history)
                cursor = snapshot.latest
                snapshot.events.forEach { applyEvent(it) }
                snapshot.pendingUi.lastOrNull()?.let { request ->
                    pendingUi = request
                    dialogInput = request.prefill.ifBlank { "" }
                }
                snapshot.editorText?.let { input = it }
                connected = true
                status = when {
                    currentState?.compacting == true -> "Compacting"
                    currentState?.streaming == true -> "Working"
                    else -> "Ready"
                }
                panel = Panel.Chat
                refreshMeta()
                AgentKeepAliveService.start(bridgeContext = bridge.applicationContext())
            } catch (error: Exception) {
                status = "Connection failed"
                addSystem("连接失败：${error.message}")
            } finally {
                connecting = false
            }
        }
    }

    LaunchedEffect(bridge) {
        connect()
    }

    fun sendExtensionCommand(text: String) {
        scope.launch {
            bridge.prompt(text).onFailure { addSystem("命令失败：${it.message}") }
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
        if (attachments.isNotEmpty()) {
            followOutput = true
            val attachmentSummary = attachments.joinToString(", ") { "[附件: ${it.name}]" }
            lines.add(ChatLine("user", listOf(text, attachmentSummary).filter { it.isNotBlank() }.joinToString("\n")))
            scope.launch {
                val behavior = if (currentState?.streaming == true || status == "Working") "steer" else null
                bridge.prompt(text, behavior, attachments).fold(
                    onSuccess = { status = "Working" },
                    onFailure = { addSystem("发送附件失败：${it.message}") }
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
            scope.launch {
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
            "/resume" -> scope.launch {
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
            "/copy" -> scope.launch {
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
                    if (exact.size == 1) scope.launch {
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
                    else scope.launch {
                        bridge.setThinking(level).fold(
                            onSuccess = { refreshMeta(); addSystem("Thinking = $level") },
                            onFailure = { addSystem("Thinking 设置失败：${it.message}") }
                        )
                    }
                }
            }
            "/session" -> {
                panel = Panel.Stats
                scope.launch { bridge.stats().onSuccess { currentStats = it }.onFailure { addSystem(it.message.orEmpty()) } }
            }
            "/hotkeys" -> addSystem(
                """移动端操作
                |• 输入 /：打开可搜索命令面板
                |• 工作中仍可直接发送：按 Pi 规则作为 steering message 排队
                |• 输入 /abort 才会中止当前 Agent
                |• 滑动离开底部：暂停跟随；回到底部自动恢复
                |• 右侧 ↑/↓：按屏幕高度翻页
                |• 长按消息：选择并复制文本
                |• 工具卡片：默认 10 行，可展开全部实时输出
                |• ! 执行 bash 并加入上下文；!! 执行但不加入上下文""".trimMargin()
            )
            "/changelog" -> addSystem(
                """Pi Android v5.16.1
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
                    scope.launch {
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
                scope.launch { bridge.files(currentPath).onSuccess { files = it }.onFailure { addSystem(it.message.orEmpty()) } }
            }
            "/diff" -> {
                panel = Panel.Diff
                scope.launch { bridge.diff().onSuccess { diffText = it.ifBlank { "没有未提交改动" } }.onFailure { diffText = "ERROR: ${it.message}" } }
            }
            "/new" -> scope.launch {
                bridge.newSession().fold(
                    onSuccess = {
                        lines.clear()
                        bridge.applyDefaultModel(models).onFailure { addSystem(it.message.orEmpty()) }
                        addSystem("已使用默认模型创建新的 Pi session")
                        refreshMeta()
                    },
                    onFailure = { addSystem("/new 失败：${it.message}") }
                )
            }
            "/compact" -> scope.launch {
                status = "Compacting"
                bridge.compact(args).fold(
                    onSuccess = { addSystem("上下文压缩完成"); refreshMeta() },
                    onFailure = { addSystem("/compact 失败：${it.message}") }
                )
                status = "Ready"
            }
            "/clone" -> scope.launch {
                bridge.cloneSession().fold(
                    onSuccess = { addSystem("当前 active branch 已克隆"); refreshMeta(); loadHistory() },
                    onFailure = { addSystem("/clone 失败：${it.message}") }
                )
            }
            "/abort" -> scope.launch {
                status = "Stopping"
                bridge.abort().fold(
                    onSuccess = { addSystem("已发送取消") },
                    onFailure = { addSystem("取消失败：${it.message}") }
                )
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
                lines.add(ChatLine("user", text))
                scope.launch {
                    val behavior = if (currentState?.streaming == true || status == "Working") "steer" else null
                    bridge.prompt(text, behavior).fold(
                        onSuccess = { status = "Working" },
                        onFailure = { addSystem("发送失败：${it.message}") }
                    )
                }
            }
        }
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (connected) {
            bridge.events(cursor).onSuccess { batch ->
                eventFailures = 0
                if (batch.gap) {
                    bridge.recoverySnapshot().fold(
                        onSuccess = { snapshot ->
                            restoreHistory(snapshot.history)
                            cursor = snapshot.latest
                            snapshot.events.forEach { applyEvent(it) }
                            snapshot.pendingUi.lastOrNull()?.let { pendingUi = it }
                            snapshot.editorText?.let { input = it }
                        },
                        onFailure = {
                            loadHistory()
                            cursor = batch.latest
                        }
                    )
                } else {
                    cursor = batch.latest
                    batch.events.forEach { event -> applyEvent(event) }
                }
            }.onFailure { error ->
                eventFailures += 1
                if (eventFailures >= 5) {
                    status = "Disconnected"
                    connected = false
                    addSystem("Bridge 连接中断（连续 $eventFailures 次）：${error.message}")
                } else {
                    delay(1_000)
                }
            }
        }
    }

    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length, followOutput, input.length) {
        if (followOutput && lines.isNotEmpty()) chatListState.scrollToRealBottom(lines.lastIndex)
    }

    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && followOutput && lines.isNotEmpty()) {
            delay(80)
            chatListState.scrollToRealBottom(lines.lastIndex)
        }
    }

    LaunchedEffect(followOutput) {
        if (followOutput) showScrollControls = false
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        var knownSession = currentState?.sessionId.orEmpty()
        while (connected) {
            delay(10_000)
            bridge.state().onSuccess { state ->
                if (knownSession.isNotBlank() && state.sessionId.isNotBlank() && state.sessionId != knownSession) {
                    loadHistory()
                }
                knownSession = state.sessionId
                currentState = state
            }
            bridge.stats().onSuccess { currentStats = it }
        }
    }

    pendingUi?.let { request ->
        ExtensionDialog(
            request = request,
            input = dialogInput,
            onInput = { dialogInput = it },
            onSelect = { value ->
                scope.launch { bridge.extensionUiResponse(request.id, value = value) }
                pendingUi = null
            },
            onConfirm = { value ->
                scope.launch { bridge.extensionUiResponse(request.id, confirmed = value) }
                pendingUi = null
            },
            onSubmit = {
                scope.launch { bridge.extensionUiResponse(request.id, value = dialogInput) }
                pendingUi = null
            },
            onDismiss = {
                scope.launch { bridge.extensionUiResponse(request.id, cancelled = true) }
                pendingUi = null
            }
        )
    }


    if (resumeOpen) {
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
                                        resumeOpen = false
                                        scope.launch {
                                            status = "Switching session"
                                            bridge.switchSession(session.path).fold(
                                                onSuccess = {
                                                    loadHistory()
                                                    refreshMeta()
                                                    status = "Ready"
                                                },
                                                onFailure = {
                                                    addSystem("切换 session 失败：${it.message}")
                                                    status = "Ready"
                                                }
                                            )
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .background(Bg)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                Panel.Chat -> ChatPanel(
                    lines = lines,
                    listState = chatListState,
                    cwd = cwd,
                    model = currentState?.let { it.modelName.ifBlank { it.modelId } }.orEmpty(),
                    status = status,
                    connected = connected,
                    onConnect = connect,
                    onSettings = { panel = Panel.Settings },
                    onFollowChange = { followOutput = it },
                    onUserScrollActivity = { showScrollControls = true },
                    onToggleTool = ::toggleTool
                )
                Panel.Models -> ModelsPanel(
                    models = models,
                    state = currentState,
                    initialSearch = modelInitialSearch,
                    defaultModelKey = defaultModelKey,
                    onBack = { panel = Panel.Chat },
                    onSetDefault = { model ->
                        bridge.saveDefaultModel(model)
                        defaultModelKey = "${model.provider}/${model.id}"
                        addSystem("新对话默认模型：$defaultModelKey")
                    },
                    onPick = { model ->
                        scope.launch {
                            bridge.setModel(model).fold(
                                onSuccess = { refreshMeta(); addSystem("模型已切换为 ${model.provider}/${model.id}") },
                                onFailure = { addSystem("切换模型失败：${it.message}") }
                            )
                        }
                    },
                    onEffort = { level ->
                        scope.launch {
                            bridge.setThinking(level).fold(
                                onSuccess = { refreshMeta(); addSystem("reasoning_effort = $level") },
                                onFailure = { addSystem("reasoning_effort 设置失败：${it.message}") }
                            )
                        }
                    }
                )
                Panel.Thinking -> ThinkingPanel(currentState?.thinkingLevel.orEmpty(), onBack = { panel = Panel.Chat }) { level ->
                    scope.launch {
                        bridge.setThinking(level).fold(
                            onSuccess = { refreshMeta(); panel = Panel.Chat; addSystem("Thinking = $level") },
                            onFailure = { addSystem("Thinking 设置失败：${it.message}") }
                        )
                    }
                }
                Panel.Bash -> BashPanel(
                    input = bashInput,
                    output = bashOutput,
                    running = bashRunning,
                    onInput = { bashInput = it },
                    onBack = { panel = Panel.Chat },
                    onRun = {
                        val command = bashInput.trim()
                        if (command.isNotBlank()) scope.launch {
                            bashRunning = true
                            bashOutput = "$ $command\n"
                            bridge.bash(command).fold(
                                onSuccess = { bashOutput += it.output + "\n[exit ${it.exitCode}]" },
                                onFailure = { bashOutput += "ERROR: ${it.message}" }
                            )
                            bashRunning = false
                            refreshMeta()
                        }
                    },
                    onAbort = { scope.launch { bridge.abortBash() } }
                )
                Panel.Files -> FilesPanel(
                    path = currentPath,
                    files = files,
                    selectedFile = selectedFile,
                    fileText = fileText,
                    onBack = { panel = Panel.Chat },
                    onOpen = { item ->
                        if (item.type == "directory") {
                            currentPath = item.path
                            selectedFile = ""
                            scope.launch { bridge.files(currentPath).onSuccess { files = it } }
                        } else {
                            selectedFile = item.path
                            scope.launch { bridge.file(item.path).onSuccess { fileText = it }.onFailure { addSystem(it.message.orEmpty()) } }
                        }
                    },
                    onUp = {
                        currentPath = currentPath.substringBeforeLast('/', "")
                        selectedFile = ""
                        scope.launch { bridge.files(currentPath).onSuccess { files = it } }
                    },
                    onText = { fileText = it },
                    onSave = {
                        if (selectedFile.isNotBlank()) scope.launch {
                            bridge.writeFile(selectedFile, fileText).fold(
                                onSuccess = { addSystem("已保存 $selectedFile") },
                                onFailure = { addSystem("保存失败：${it.message}") }
                            )
                        }
                    }
                )
                Panel.Diff -> TextPanel("/diff", diffText, onBack = { panel = Panel.Chat })
                Panel.Stats -> StatsPanel(currentStats, currentState, onBack = { panel = Panel.Chat })
                Panel.Themes -> ThemesPanel(
                    selected = themeMode,
                    onBack = { panel = Panel.Chat },
                    onSelect = onTheme
                )
                Panel.Settings -> SettingsPanel(
                    cwd = cwd,
                    launchCommand = launchCommand,
                    connected = connected,
                    autoCompaction = currentState?.autoCompactionEnabled ?: true,
                    onCwd = { cwd = it },
                    onLaunch = { launchCommand = it },
                    onConnect = connect,
                    onAutoCompaction = { enabled ->
                        scope.launch {
                            bridge.setAutoCompaction(enabled).fold(
                                onSuccess = { refreshMeta(); addSystem("自动压缩：${if (enabled) "开启" else "关闭"}") },
                                onFailure = { addSystem("自动压缩设置失败：${it.message}") }
                            )
                        }
                    },
                    onBack = { panel = Panel.Chat }
                )
            }

            if (panel == Panel.Chat && showScrollControls) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = (-54).dp)
                        .padding(end = 4.dp, bottom = 6.dp)
                        .width(40.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).clickable {
                            followOutput = false
                            showScrollControls = true
                            scope.launch { chatListState.scrollBy(-chatListState.pageDistance()) }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↑", color = LocalPiColors.current.scrollText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).clickable {
                            scope.launch {
                                val distance = chatListState.pageDistance()
                                chatListState.scrollBy(distance)
                                followOutput = !chatListState.canScrollForward
                                showScrollControls = !chatListState.canScrollForward
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↓", color = LocalPiColors.current.scrollText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (panel == Panel.Chat && input.startsWith("/")) {
                CommandPalette(
                    query = input,
                    local = localCommands,
                    remote = remoteCommands,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onPick = { name, remote ->
                        input = ""
                        if (remote || name == "import") {
                            input = "/$name "
                        } else {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            executeInput("/$name")
                        }
                    }
                )
            }
        }

        if (panel == Panel.Chat) {
            Composer(
                value = input,
                busy = busy,
                attachments = pendingAttachments,
                attachmentNotice = attachmentNotice,
                focusRequester = composerFocusRequester,
                onValue = { input = it },
                onAttach = {
                    attachmentNotice = ""
                    filePicker.launch(arrayOf("*/*"))
                },
                onRemoveAttachment = { attachment -> pendingAttachments.remove(attachment) },
                onPrimary = {
                    // Sending is never abort. During a run executeInput() sends a
                    // steering message, matching Pi's Enter behavior. Abort is the
                    // explicit /abort command.
                    val value = input
                    if (value.trimStart().startsWith("/")) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                    val attachments = pendingAttachments.toList()
                    input = ""
                    pendingAttachments.clear()
                    attachmentNotice = ""
                    executeInput(value, attachments)
                }
            )
        }

        Footer(currentState, currentStats) {
            panel = Panel.Chat
            composerFocusRequest++
        }
    }
}

@Composable
private fun TerminalHeader(
    cwd: String,
    model: String,
    status: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(HeaderBg)
            .border(1.dp, LocalPiColors.current.headerDivider)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("~/", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Text(cwd.substringAfterLast('/').ifBlank { "home" }, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = if (connected) onSettings else onConnect) {
                Text(if (connected) "⋮" else "Connect", color = if (connected) TextMuted else Accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Model: ${model.ifBlank { "—" }}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(status, color = if (status == "Ready") Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun LazyListState.pageDistance(): Float {
    val layout = layoutInfo
    return ((layout.viewportEndOffset - layout.viewportStartOffset) * 0.82f).coerceAtLeast(240f)
}

private suspend fun LazyListState.scrollToRealBottom(lastIndex: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) {
        scrollToItem(lastIndex)
    }
    repeat(8) {
        val moved = scrollBy(1_000_000f)
        if (kotlin.math.abs(moved) < 0.5f || !canScrollForward) return
    }
}

@Composable
private fun ChatPanel(
    lines: List<ChatLine>,
    listState: LazyListState,
    cwd: String,
    model: String,
    status: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onUserScrollActivity: () -> Unit,
    onToggleTool: (String) -> Unit
) {
    fun isAtBottom(): Boolean = !listState.canScrollForward

    val userScrollLock = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    onFollowChange(false)
                    onUserScrollActivity()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && isAtBottom()) onFollowChange(true)
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom() }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) onFollowChange(true) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp)
            .nestedScroll(userScrollLock),
        contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "session-meta") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("~/", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(
                        cwd.substringAfterLast('/').ifBlank { "home" },
                        color = Blue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (connected) "⋮" else if (status == "Disconnected" || status.endsWith("failed")) "Connect" else "Connecting…",
                        color = if (connected) TextMuted else Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { if (connected) onSettings() else onConnect() }.padding(8.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Model: ${model.ifBlank { "—" }}",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        status,
                        color = if (status == "Ready") Accent else if (status == "Working") Blue else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (lines.isEmpty()) {
            item {
                Text(
                    "Pi Touch GUI\n输入 / 调出真实命令。",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
        items(lines) { line ->
            val fullText = line.text.trimEnd()
            val fullLines = fullText.lines()
            val isTool = line.role.startsWith("tool")
            val hasHiddenToolLines = isTool && fullLines.size > 10
            val visibleText = if (hasHiddenToolLines && line.collapsed) {
                if (line.role == "tool-draft") {
                    (fullLines.take(2) + "… ${fullLines.size - 9} 行生成中 …" + fullLines.takeLast(7)).joinToString("\n")
                } else {
                    fullLines.take(10).joinToString("\n")
                }
            } else {
                fullText
            }
            SelectionContainer {
                when (line.role) {
                "user" -> Text(
                    visibleText,
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().background(UserBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 12.dp)
                )
                "assistant" -> PiMarkdown(
                    visibleText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 4.dp)
                )
                "thinking" -> Text(
                    markdownText(visibleText, Blue),
                    color = ThinkingText,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 3.dp)
                )
                "tool", "tool-draft" -> Column(
                    Modifier.fillMaxWidth().background(ToolBg, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 10.dp)
                ) {
                    Text(
                        visibleText,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    if (hasHiddenToolLines) {
                        TextButton(
                            onClick = { onToggleTool(line.toolCallId) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (line.collapsed) "展开全部 ↓" else "收起 ↑",
                                color = Blue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                    else -> Text(
                        visibleText,
                        color = if (line.text.contains("失败") || line.text.contains("ERROR")) Danger else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPalette(
    query: String,
    local: List<LocalCommand>,
    remote: List<PiCommand>,
    modifier: Modifier = Modifier,
    onPick: (String, Boolean) -> Unit
) {
    val needle = query.removePrefix("/").trim().lowercase()
    val localNames = local.map { it.name }.toSet()
    val choices = buildList<Pair<LocalCommand, Boolean>> {
        local.filter { it.name.contains(needle) }.forEach { add(it to false) }
        remote.filter { !it.name.startsWith("__") && it.name !in localNames && it.name.contains(needle, ignoreCase = true) }.forEach {
            add(LocalCommand(it.name, it.description.ifBlank { it.source }) to true)
        }
    }.take(64)
    if (choices.isEmpty()) return
    val paletteState = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val allowedHeight = minOf(360.dp, (maxHeight - 8.dp).coerceAtLeast(96.dp))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth()
                .heightIn(max = allowedHeight)
                .background(PanelBg, RoundedCornerShape(10.dp))
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .padding(vertical = 3.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pi Commands", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${choices.size} · 上下滑动", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            LazyColumn(
                state = paletteState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 3.dp)
            ) {
                items(choices) { choice ->
                    val cmd = choice.first
                    val remote = choice.second
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(cmd.name, remote) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("/${cmd.name}", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(112.dp))
                        Text(cmd.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    attachments: List<PiAttachment>,
    attachmentNotice: String,
    focusRequester: FocusRequester,
    onValue: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (PiAttachment) -> Unit,
    onPrimary: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(LocalPiColors.current.composerBg)) {
        if (attachments.isNotEmpty() || attachmentNotice.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.forEach { attachment ->
                    Text(
                        "${attachment.name}${if (attachment.byteCount >= 0) " · ${compactCount(attachment.byteCount)}B" else ""}  ×",
                        color = Blue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.background(CardBg, RoundedCornerShape(5.dp))
                            .clickable { onRemoveAttachment(attachment) }
                            .padding(horizontal = 7.dp, vertical = 5.dp)
                    )
                }
                if (attachmentNotice.isNotBlank()) Text(attachmentNotice, color = Danger, fontSize = 10.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
        TextButton(
            onClick = onAttach,
            modifier = Modifier.width(44.dp).height(52.dp),
            contentPadding = PaddingValues(0.dp),
            enabled = true,
            colors = ButtonDefaults.textButtonColors(contentColor = Blue, disabledContentColor = TextMuted)
        ) {
            Text("＋", fontFamily = FontFamily.Monospace, fontSize = 22.sp)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 132.dp).focusRequester(focusRequester),
            textStyle = TextStyle(
                color = TextMain,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            placeholder = { Text(if (busy) "Pi 工作中，输入消息可继续发送；/abort 停止" else "输入消息或 / 命令…", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onPrimary() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )
        TextButton(
            onClick = onPrimary,
            modifier = Modifier.width(52.dp).height(52.dp),
            contentPadding = PaddingValues(0.dp),
            enabled = busy || value.isNotBlank() || attachments.isNotEmpty(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = Blue,
                disabledContentColor = LocalPiColors.current.disabledAction
            )
        ) {
            Text("↵", fontFamily = FontFamily.Monospace, fontSize = 20.sp)
        }
        }
    }
}

private fun compactCount(value: Long): String = when {
    value >= 1_000_000_000 -> "${(value / 1_000_000_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}B"
    value >= 1_000_000 -> "${(value / 1_000_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}M"
    value >= 1_000 -> "${(value / 1_000.0).let { if (it >= 10) "%.0f".format(java.util.Locale.US, it) else "%.1f".format(java.util.Locale.US, it) }}k"
    else -> value.toString()
}

@Composable
private fun Footer(state: PiState?, stats: PiStats?, onFocusComposer: () -> Unit) {
    val parts = if (stats == null) {
        listOf("—/—")
    } else {
        buildList {
            if (stats.inputTokens > 0) add("↑${compactCount(stats.inputTokens)}")
            if (stats.outputTokens > 0) add("↓${compactCount(stats.outputTokens)}")
            if (stats.cacheRead > 0) add("R${compactCount(stats.cacheRead)}")
            if (stats.cacheWrite > 0) add("W${compactCount(stats.cacheWrite)}")
            if ((stats.cacheRead > 0 || stats.cacheWrite > 0) && stats.latestCacheHitRate >= 0) {
                add("CH${"%.1f".format(java.util.Locale.US, stats.latestCacheHitRate)}%")
            }
            val subscription = state?.provider == "openai-codex" ||
                state?.provider == "kimi-coding" ||
                state?.provider?.contains("copilot", ignoreCase = true) == true
            if (stats.cost > 0 || subscription) {
                add("\$${"%.3f".format(java.util.Locale.US, stats.cost)}${if (subscription) " (sub)" else ""}")
            }
            val context = if (stats.contextPercent >= 0 && stats.contextWindow > 0) {
                "${"%.1f".format(java.util.Locale.US, stats.contextPercent)}%/${compactCount(stats.contextWindow)}"
            } else {
                "—/—"
            }
            add(context + if (state?.autoCompactionEnabled == true) " (auto)" else "")
        }
    }
    val scroll = rememberScrollState()
    Box(
        Modifier.fillMaxWidth().background(Bg).clickable(onClick = onFocusComposer)
            .navigationBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            parts.joinToString(" "),
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(scroll)
        )
    }
}

@Composable
private fun PanelHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Blue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("返回", color = TextMuted) }
    }
}

@Composable
private fun ModelsPanel(
    models: List<PiModel>,
    state: PiState?,
    initialSearch: String,
    defaultModelKey: String,
    onBack: () -> Unit,
    onSetDefault: (PiModel) -> Unit,
    onPick: (PiModel) -> Unit,
    onEffort: (String) -> Unit
) {
    val effortLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    var search by remember(initialSearch) { mutableStateOf(initialSearch) }
    val tokens = search.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val visibleModels = models.filter { model ->
        val searchable = "${model.provider} ${model.id} ${model.name}".lowercase()
        tokens.all { it in searchable }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/model", onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 14.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(6.dp)).padding(12.dp)
                ) {
                    Text("reasoning_effort", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Pi thinking level → provider reasoning_effort",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp, bottom = 6.dp)
                    )
                    effortLevels.forEach { level ->
                        val selected = state?.thinkingLevel == level
                        Row(
                            Modifier.fillMaxWidth().clickable { onEffort(level) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(level, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(if (selected) "✓ 当前" else "选择", color = if (selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("搜索 provider、模型名称或 ID") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
                Text(
                    "Models · ${visibleModels.size}/${models.size}",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(visibleModels) { model ->
                val selected = state?.provider == model.provider && state.modelId == model.id
                val isDefault = defaultModelKey == "${model.provider}/${model.id}"
                Row(
                    Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(6.dp)).clickable { onPick(model) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name.ifBlank { model.id }, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("${model.provider}/${model.id}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (selected) "✓ 当前" else "选择", color = if (selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        TextButton(onClick = { onSetDefault(model) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text(if (isDefault) "★ 新对话默认" else "设为默认", color = if (isDefault) Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingPanel(current: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/thinking", onBack)
        levels.forEach { level ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(level) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(level, color = TextMain, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                if (level == current) Text("✓", color = Accent)
            }
        }
    }
}

@Composable
private fun BashPanel(
    input: String,
    output: String,
    running: Boolean,
    onInput: (String) -> Unit,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onAbort: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/run · Pi RPC bash", onBack)
        Text(
            output.ifBlank { "命令通过 Pi 的 bash RPC 执行，并进入 Pi session 上下文。" },
            color = TextMain,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)
        )
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.weight(1f), singleLine = true, label = { Text("$ command") })
            if (running) Button(onClick = onAbort, colors = ButtonDefaults.buttonColors(containerColor = LocalPiColors.current.stopButtonBg)) { Text("停止") }
            else Button(onClick = onRun, enabled = input.isNotBlank()) { Text("执行") }
        }
    }
}

@Composable
private fun FilesPanel(
    path: String,
    files: List<PiFile>,
    selectedFile: String,
    fileText: String,
    onBack: () -> Unit,
    onOpen: (PiFile) -> Unit,
    onUp: () -> Unit,
    onText: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/files · /$path", onBack)
        if (selectedFile.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUp, enabled = path.isNotBlank()) { Text("↑ 上级") }
                Text(path.ifBlank { "/" }, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(files) { file ->
                    Text(
                        (if (file.type == "directory") "[DIR]  " else "[FILE] ") + file.name,
                        color = if (file.type == "directory") Blue else TextMain,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(vertical = 11.dp)
                    )
                }
            }
        } else {
            Text(selectedFile, color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedTextField(
                value = fileText,
                onValueChange = onText,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMain)
            )
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("保存") }
                TextButton(onClick = { onOpen(PiFile("..", "directory", path)) }) { Text("返回文件列表") }
            }
        }
    }
}

@Composable
private fun TextPanel(title: String, text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader(title, onBack)
        Text(
            text.ifBlank { "加载中…" },
            color = TextMain,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)
        )
    }
}

@Composable
private fun StatsPanel(stats: PiStats?, state: PiState?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/session", onBack)
        val rows = listOf(
            "Session" to (state?.sessionName?.ifBlank { state.sessionId }.orEmpty()),
            "File" to (stats?.sessionFile ?: state?.sessionFile.orEmpty()),
            "Messages" to (stats?.totalMessages?.toString() ?: "—"),
            "Input tokens" to (stats?.inputTokens?.toString() ?: "—"),
            "Output tokens" to (stats?.outputTokens?.toString() ?: "—"),
            "Cache read" to (stats?.cacheRead?.toString() ?: "—"),
            "Cost" to (stats?.let { "$${"%.4f".format(it.cost)}" } ?: "—"),
            "Context" to (stats?.takeIf { it.contextPercent >= 0 }?.let { "${it.contextTokens}/${it.contextWindow} (${"%.1f".format(it.contextPercent)}%)" } ?: "—")
        )
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(label, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                Text(value, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ThemesPanel(
    selected: PiThemeMode,
    onBack: () -> Unit,
    onSelect: (PiThemeMode) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        PanelHeader("Themes", onBack)
        Text(
            "主题会立即应用并自动保存。暗色主题保持原有配色。",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PiThemeMode.entries.forEach { mode ->
                val preview = colorsFor(mode)
                Row(
                    Modifier.fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(8.dp))
                        .border(1.dp, if (mode == selected) Accent else Border, RoundedCornerShape(8.dp))
                        .clickable { onSelect(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.width(58.dp).height(38.dp)
                            .background(preview.bg, RoundedCornerShape(5.dp))
                            .border(1.dp, preview.border, RoundedCornerShape(5.dp))
                            .padding(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(12.dp).height(24.dp).background(preview.userBg, RoundedCornerShape(2.dp)))
                        Box(Modifier.width(12.dp).height(24.dp).background(preview.toolBg, RoundedCornerShape(2.dp)))
                        Box(Modifier.width(12.dp).height(24.dp).background(preview.blue, RoundedCornerShape(2.dp)))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(mode.displayName, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(mode.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    Text(if (mode == selected) "✓ 当前" else "选择", color = if (mode == selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    cwd: String,
    launchCommand: String,
    connected: Boolean,
    autoCompaction: Boolean,
    onCwd: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onConnect: () -> Unit,
    onAutoCompaction: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelHeader("/settings", onBack)
        OutlinedTextField(
            value = cwd,
            onValueChange = onCwd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("Pi 工作目录") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        )
        OutlinedTextField(
            value = launchCommand,
            onValueChange = onLaunch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("Pi RPC 启动命令") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        )
        Row(
            Modifier.fillMaxWidth().clickable(enabled = connected) { onAutoCompaction(!autoCompaction) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动上下文压缩", color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("接近模型上下文上限时自动生成 compaction summary", color = TextMuted, fontSize = 11.sp)
            }
            Text(if (autoCompaction) "ON" else "OFF", color = if (autoCompaction) Accent else TextMuted, fontFamily = FontFamily.Monospace)
        }
        Text(
            "默认直接使用 Termux 中的 Pi。不要删掉 --mode rpc；Android 的 /resume、/tree、/fork 依赖 -e ~/.pi/android/pi-android-mobile.ts。",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(14.dp)
        )
        Button(onClick = onConnect, modifier = Modifier.padding(14.dp)) { Text(if (connected) "重新连接" else "连接 Pi") }
    }
}

@Composable
private fun ExtensionDialog(
    request: PiUiRequest,
    input: String,
    onInput: (String) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    when (request.method) {
        "select" -> {
            val isSessionTree = request.title == "Session Tree"
            var filter by remember(request.id) { mutableStateOf("") }
            val tokens = filter.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val visibleOptions = if (tokens.isEmpty()) request.options else request.options.filter { option ->
                val searchable = option.lowercase()
                tokens.all { it in searchable }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(request.title.ifBlank { "选择" }) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        if (isSessionTree) {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                placeholder = { Text("搜索消息、标签或节点 ID") },
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = if (isSessionTree) 500.dp else 460.dp)) {
                            items(visibleOptions) { option ->
                                Text(
                                    option,
                                    color = if (isSessionTree && "●" in option) Accent else TextMain,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 9.dp)
                                )
                            }
                        }
                        if (visibleOptions.isEmpty()) {
                            Text("没有匹配的节点", color = TextMuted, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
        "confirm" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title.ifBlank { "确认" }) },
            text = { Text(request.message) },
            confirmButton = { TextButton(onClick = { onConfirm(true) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { onConfirm(false) }) { Text("取消") } }
        )
        "input", "editor" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title.ifBlank { "输入" }) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (request.method == "editor") 180.dp else 56.dp),
                    placeholder = { Text(request.placeholder) },
                    singleLine = request.method == "input"
                )
            },
            confirmButton = { TextButton(onClick = onSubmit) { Text("确定") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}
