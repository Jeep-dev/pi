from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text()
main = replace_once(
    main,
    '    var launchCommand by rememberSaveable(session.androidSessionId) { mutableStateOf(session.launchCommand) }\n',
    '    var launchCommand by rememberSaveable(session.androidSessionId) { mutableStateOf(session.launchCommand) }\n'
    '    var startupArguments by rememberSaveable(session.androidSessionId) { mutableStateOf(session.startupArguments) }\n',
    'startup argument state',
)

pattern = re.compile(r'^\s{20}Panel\.Settings -> SettingsPanel\(.*\)$', re.M)
replacement = '''                    Panel.Settings -> SettingsPanel(
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
                    )'''
main, count = pattern.subn(replacement, main, count=1)
if count != 1:
    raise SystemExit(f"settings call: expected exactly one match, got {count}")

start = main.index('@Composable\nprivate fun SettingsPanel(')
end = main.index('\n\n@Composable\nprivate fun ExtensionDialog', start)
settings = r'''@Composable
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
}'''
main = main[:start] + settings + main[end:]

main = replace_once(
    main,
    '"""Pi Android v5.19.16\n                |• 丢弃恢复快照与实时事件的重复/过期事件，避免旧回答串到新消息后面',
    '"""Pi Android v5.19.17\n                |• /settings 显示并可编辑每个 Session 的附加启动参数\n                |• 按 Pi 原生 CLI 分类提示常用模型、工具、资源和提示词启动参数\n                |• 丢弃恢复快照与实时事件的重复/过期事件，避免旧回答串到新消息后面',
    'changelog version',
)
MAIN.write_text(main)
print("v5.19.17 startup settings repair applied")
