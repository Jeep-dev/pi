package com.piandroid

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permission = "com.termux.permission.RUN_COMMAND"
    private val permissionRequestCode = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestTermuxPermissionIfNeeded()
        setContent { PiTouchApp(PiBridge(this)) }
    }

    private fun requestTermuxPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val installed = runCatching { packageManager.getPackageInfo("com.termux", 0) }.isSuccess
        if (installed && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), permissionRequestCode)
        }
    }
}

private enum class Panel { Chat, Models, Thinking, Bash, Files, Diff, Stats, Settings }
private data class ChatLine(val role: String, val text: String, val streaming: Boolean = false)
private data class LocalCommand(val name: String, val description: String)

private val Bg = Color(0xFF050607)
private val PanelBg = Color(0xFF0D1116)
private val CardBg = Color(0xFF171B21)
private val UserBg = Color(0xFF30313A)
private val Border = Color(0xFF284864)
private val Accent = Color(0xFF70E69A)
private val Blue = Color(0xFF79C5FF)
private val TextMain = Color(0xFFE8EAF0)
private val TextMuted = Color(0xFF858C96)
private val Danger = Color(0xFFFF8D8D)

@Composable
private fun PiTouchApp(bridge: PiBridge) {
    val scheme = darkColorScheme(
        background = Bg,
        surface = Bg,
        primary = Blue,
        onBackground = TextMain,
        onSurface = TextMain
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            PiScreen(bridge)
        }
    }
}

@Composable
private fun PiScreen(bridge: PiBridge) {
    var cwd by rememberSaveable { mutableStateOf("/data/data/com.termux/files/home") }
    var launchCommand by rememberSaveable { mutableStateOf("pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts") }
    var input by rememberSaveable { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var panel by remember { mutableStateOf(Panel.Chat) }
    var currentState by remember { mutableStateOf<PiState?>(null) }
    var currentStats by remember { mutableStateOf<PiStats?>(null) }
    var models by remember { mutableStateOf<List<PiModel>>(emptyList()) }
    var remoteCommands by remember { mutableStateOf<List<PiCommand>>(emptyList()) }
    var cursor by remember { mutableLongStateOf(0L) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val scope = rememberCoroutineScope()
    val chatListState = rememberLazyListState()

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

    val localCommands = remember {
        listOf(
            LocalCommand("model", "切换真实 Pi 模型"),
            LocalCommand("thinking", "切换真实 thinking level"),
            LocalCommand("tree", "打开真实 Session Tree"),
            LocalCommand("new", "新建 Pi session"),
            LocalCommand("session", "查看 session/token/cost"),
            LocalCommand("run", "通过 Pi RPC 执行 bash"),
            LocalCommand("files", "浏览当前项目文件"),
            LocalCommand("diff", "查看当前 Git diff"),
            LocalCommand("compact", "压缩当前上下文"),
            LocalCommand("clone", "克隆当前 active branch"),
            LocalCommand("abort", "停止当前 Agent 操作"),
            LocalCommand("settings", "连接与启动设置")
        )
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

    fun appendAssistant(delta: String) {
        if (delta.isEmpty()) return
        val last = lines.lastOrNull()
        if (last?.role == "assistant" && last.streaming) {
            lines[lines.lastIndex] = last.copy(text = last.text + delta)
        } else {
            lines.add(ChatLine("assistant", delta, streaming = true))
        }
    }

    fun settleAssistant() {
        if (lines.isNotEmpty()) {
            val last = lines.last()
            if (last.role == "assistant" && last.streaming) lines[lines.lastIndex] = last.copy(streaming = false)
        }
    }

    val connect: () -> Unit = {
        scope.launch {
            status = "Installing bridge"
            connected = false
            bridge.installAndStartBridge().fold(
                onSuccess = {
                    status = "Waiting for bridge"
                    bridge.waitForBridge().fold(
                        onSuccess = {
                            status = "Starting Pi"
                            bridge.start(cwd.trim(), launchCommand.trim()).fold(
                                onSuccess = {
                                    currentState = it
                                    connected = true
                                    status = "Ready"
                                    panel = Panel.Chat
                                    addSystem("Pi RPC 已连接：${it.modelName.ifBlank { it.modelId }}")
                                    refreshMeta()
                                },
                                onFailure = {
                                    status = "Pi failed"
                                    addSystem("启动失败：${it.message}")
                                }
                            )
                        },
                        onFailure = {
                            status = "Bridge failed"
                            addSystem(it.message ?: "Bridge 启动失败")
                        }
                    )
                },
                onFailure = {
                    status = "Bridge failed"
                    addSystem(it.message ?: "Bridge 安装失败")
                }
            )
        }
    }

    fun executeInput(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        if (!connected && !text.startsWith("/settings")) {
            addSystem("还没有连接 Pi。点顶部 Connect 或输入 /settings。")
            return
        }
        val command = text.substringBefore(' ')
        val args = text.substringAfter(' ', "").trim()
        when (command) {
            "/model" -> panel = Panel.Models
            "/thinking" -> panel = Panel.Thinking
            "/session" -> {
                panel = Panel.Stats
                scope.launch { bridge.stats().onSuccess { currentStats = it }.onFailure { addSystem(it.message.orEmpty()) } }
            }
            "/run" -> {
                panel = Panel.Bash
                if (args.isNotBlank()) {
                    bashInput = args
                    scope.launch {
                        bashRunning = true
                        bridge.bash(args).fold(
                            onSuccess = { bashOutput = "$ ${args}\n${it.output}\n[exit ${it.exitCode}]" },
                            onFailure = { bashOutput = "ERROR: ${it.message}" }
                        )
                        bashRunning = false
                        refreshMeta()
                    }
                }
            }
            "/files" -> {
                panel = Panel.Files
                scope.launch {
                    bridge.files(currentPath).onSuccess { files = it }.onFailure { addSystem(it.message.orEmpty()) }
                }
            }
            "/diff" -> {
                panel = Panel.Diff
                scope.launch {
                    bridge.diff().onSuccess { diffText = it.ifBlank { "没有未提交改动" } }.onFailure { diffText = "ERROR: ${it.message}" }
                }
            }
            "/new" -> scope.launch {
                bridge.newSession().fold(
                    onSuccess = { lines.clear(); addSystem("已创建新的 Pi session"); refreshMeta() },
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
                    onSuccess = { addSystem("当前 active branch 已克隆"); refreshMeta() },
                    onFailure = { addSystem("/clone 失败：${it.message}") }
                )
            }
            "/abort" -> scope.launch {
                bridge.abort().fold(
                    onSuccess = { status = "Ready"; addSystem("已发送 abort") },
                    onFailure = { addSystem("abort 失败：${it.message}") }
                )
            }
            "/settings" -> panel = Panel.Settings
            else -> {
                lines.add(ChatLine("user", text))
                scope.launch {
                    bridge.prompt(text, if (currentState?.streaming == true) "steer" else null).fold(
                        onSuccess = { status = "Running" },
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
                cursor = batch.latest
                batch.events.forEach { event ->
                    when (event.type) {
                        "agent_start" -> status = "Running"
                        "agent_end", "agent_settled" -> {
                            settleAssistant()
                            status = "Ready"
                        }
                        "message_update" -> when (event.subtype) {
                            "text_delta" -> appendAssistant(event.text)
                            "thinking_delta" -> Unit
                            "toolcall_start" -> addSystem(event.text)
                        }
                        "tool_execution_start", "tool_execution_end", "stderr", "process_exit", "extension_error" -> addSystem(event.text)
                        "compaction_start" -> status = "Compacting"
                        "compaction_end" -> status = "Ready"
                        "extension_ui_request" -> {
                            val req = event.uiRequest
                            when (req?.method) {
                                "notify" -> addSystem(req.message)
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
            }.onFailure {
                status = "Disconnected"
                connected = false
                addSystem("Bridge 连接中断：${it.message}")
            }
            delay(180)
        }
    }

    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) chatListState.animateScrollToItem(lines.lastIndex)
    }

    LaunchedEffect(connected, status) {
        if (!connected) return@LaunchedEffect
        while (connected) {
            delay(2500)
            bridge.state().onSuccess { currentState = it }
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .background(Bg)
    ) {
        TerminalHeader(
            cwd = cwd,
            model = currentState?.let { it.modelName.ifBlank { it.modelId } }.orEmpty(),
            status = status,
            connected = connected,
            onConnect = connect,
            onSettings = { panel = Panel.Settings }
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                Panel.Chat -> ChatPanel(lines, chatListState)
                Panel.Models -> ModelsPanel(models, currentState, onBack = { panel = Panel.Chat }) { model ->
                    scope.launch {
                        bridge.setModel(model).fold(
                            onSuccess = { refreshMeta(); panel = Panel.Chat; addSystem("模型已切换为 ${model.provider}/${model.id}") },
                            onFailure = { addSystem("切换模型失败：${it.message}") }
                        )
                    }
                }
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
                Panel.Settings -> SettingsPanel(
                    cwd = cwd,
                    launchCommand = launchCommand,
                    connected = connected,
                    onCwd = { cwd = it },
                    onLaunch = { launchCommand = it },
                    onConnect = connect,
                    onBack = { panel = Panel.Chat }
                )
            }

            if (panel == Panel.Chat && input.startsWith("/")) {
                CommandPalette(
                    query = input,
                    local = localCommands,
                    remote = remoteCommands,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onPick = { name, remote ->
                        input = ""
                        if (remote && name != "tree") input = "/$name "
                        else executeInput("/$name")
                    }
                )
            }
        }

        Footer(currentState, currentStats, status)

        if (panel == Panel.Chat) {
            Composer(
                value = input,
                onValue = { input = it },
                onSlash = { input = if (input.startsWith("/")) "" else "/" },
                onSend = {
                    val value = input
                    input = ""
                    executeInput(value)
                }
            )
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
            .background(Color(0xFF090C0F))
            .border(1.dp, Color(0xFF151B21))
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
            Text(status, color = if (connected) Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChatPanel(lines: List<ChatLine>, listState: androidx.compose.foundation.lazy.LazyListState) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
            when (line.role) {
                "user" -> Text(
                    line.text,
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().background(UserBg, RoundedCornerShape(4.dp)).padding(14.dp)
                )
                "assistant" -> Text(
                    line.text,
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                )
                else -> Text(
                    line.text,
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
        remote.filter { it.name !in localNames && it.name.contains(needle, ignoreCase = true) }.forEach {
            add(LocalCommand(it.name, it.description.ifBlank { it.source }) to true)
        }
    }.take(9)
    if (choices.isEmpty()) return
    Column(
        modifier
            .padding(10.dp)
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Text("Command Palette", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 8.dp))
        choices.forEach { (cmd, remote) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(cmd.name, remote) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("/${cmd.name}", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(100.dp))
                Text(cmd.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Composer(value: String, onValue: (String) -> Unit, onSlash: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF07090B)).border(1.dp, Color(0xFF19232C)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = onSlash,
            modifier = Modifier.width(46.dp).height(48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Blue)
        ) { Text("/", fontFamily = FontFamily.Monospace, fontSize = 21.sp) }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入消息或 / 命令…", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        Button(onClick = onSend, modifier = Modifier.height(48.dp), enabled = value.isNotBlank()) {
            Text("↵", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Footer(state: PiState?, stats: PiStats?, status: String) {
    val context = if (stats != null && stats.contextPercent >= 0) "${"%.1f".format(stats.contextPercent)}%" else "—"
    Row(
        Modifier.fillMaxWidth().background(Bg).border(1.dp, Color(0xFF23384B)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ctx $context", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("msg ${state?.messageCount ?: 0}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(status, color = if (status == "Ready") Accent else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
private fun ModelsPanel(models: List<PiModel>, state: PiState?, onBack: () -> Unit, onPick: (PiModel) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("/model", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(models) { model ->
                val selected = state?.provider == model.provider && state.modelId == model.id
                Row(
                    Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(6.dp)).clickable { onPick(model) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name.ifBlank { model.id }, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("${model.provider}/${model.id}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    Text(if (selected) "✓ 当前" else "选择", color = if (selected) Accent else Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ThinkingPanel(current: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh")
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
            OutlinedTextField(input, onInput, Modifier.weight(1f), singleLine = true, label = { Text("$ command") })
            if (running) Button(onClick = onAbort, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512C2C))) { Text("停止") }
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
        PanelHeader("/files · /${path}", onBack)
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
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMain)
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
private fun SettingsPanel(
    cwd: String,
    launchCommand: String,
    connected: Boolean,
    onCwd: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelHeader("/settings", onBack)
        OutlinedTextField(
            value = cwd,
            onValueChange = onCwd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("Pi 工作目录") },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        )
        OutlinedTextField(
            value = launchCommand,
            onValueChange = onLaunch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            label = { Text("Pi RPC 启动命令") },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        )
        Text(
            "默认直接使用 Termux 中的 Pi。若你的 Pi 在 proot/Ubuntu 中，把这里改成从 Termux 能执行的完整 RPC 启动命令。不要删掉 --mode rpc；如需真实 /tree，请保留 -e ~/.pi/android/pi-android-mobile.ts。",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(14.dp)
        )
        Button(onClick = onConnect, modifier = Modifier.padding(14.dp)) {
            Text(if (connected) "重新连接" else "连接 Pi")
        }
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
        "select" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title.ifBlank { "选择" }) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(request.options) { option ->
                        Text(
                            option,
                            color = TextMain,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 11.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
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
