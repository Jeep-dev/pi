from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
ANDROID_YML = ROOT / ".github/workflows/android.yml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text()
main = replace_once(
    main,
    "    var followOutput by remember { mutableStateOf(true) }\n    var showScrollControls by remember { mutableStateOf(false) }\n",
    "    var followOutput by remember { mutableStateOf(true) }\n    var showScrollControls by remember { mutableStateOf(false) }\n    // Highest bridge event sequence already rendered by this PiScreen.\n    var lastAppliedEventSeq by remember { mutableLongStateOf(0L) }\n",
    "event watermark state",
)
main = replace_once(
    main,
    "    suspend fun applyEvent(event: PiEvent, recovering: Boolean = false) {\n        when (event.type) {\n",
    "    suspend fun applyEvent(event: PiEvent, recovering: Boolean = false) {\n        // Recovery snapshots can overlap live event batches. Drop stale events so an old\n        // message_end can never be appended after a newer user request.\n        if (event.seq > 0L) {\n            if (event.seq <= lastAppliedEventSeq) return\n            lastAppliedEventSeq = event.seq\n        }\n        when (event.type) {\n",
    "event dedupe gate",
)
main = replace_once(
    main,
    "    suspend fun applyRuntimeReady(ready: PiRuntimeReady) {\n        val state = ready.state\n        applyRuntimeState(state, ready.runtimeCwd)\n",
    "    suspend fun applyRuntimeReady(ready: PiRuntimeReady) {\n        val state = ready.state\n        // A restarted Bridge begins a new sequence epoch from zero.\n        if (ready.snapshot.latest < lastAppliedEventSeq) lastAppliedEventSeq = 0L\n        applyRuntimeState(state, ready.runtimeCwd)\n",
    "ready watermark reset",
)
main = replace_once(
    main,
    "                is PiRuntimeUpdate.Snapshot -> {\n                    restoreHistory(update.value.history, preservePending = true)\n",
    "                is PiRuntimeUpdate.Snapshot -> {\n                    if (update.value.latest < lastAppliedEventSeq) lastAppliedEventSeq = 0L\n                    restoreHistory(update.value.history, preservePending = true)\n",
    "snapshot watermark reset",
)
old_scroll = '''    LaunchedEffect(chatListState) {
        snapshotFlow {
            Triple(
                lines.size,
                lines.sumOf { if (it.streaming) it.text.length.toLong() else 0L },
                followOutput
            )
        }
            .distinctUntilChanged()
            .conflate()
            .collect { (lineCount, _, shouldFollow) ->
                if (shouldFollow && lineCount > 0) {
                    delay(32)
                    if (followOutput && lines.isNotEmpty()) chatListState.scrollToRealBottom()
                }
            }
    }
'''
new_scroll = '''    LaunchedEffect(chatListState) {
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
'''
main = replace_once(main, old_scroll, new_scroll, "visible-content auto follow")
main = replace_once(
    main,
    '"""Pi Android v5.19.15\n                |• 工具卡片改为接近原生 Pi 的中性终端布局，不再把整条命令染成绿色',
    '"""Pi Android v5.19.16\n                |• 丢弃恢复快照与实时事件的重复/过期事件，避免旧回答串到新消息后面\n                |• 自动跟随监听完整可见内容；工具参数、输出和状态增长也会持续贴底\n                |• 工具卡片改为接近原生 Pi 的中性终端布局，不再把整条命令染成绿色',
    "changelog version",
)
MAIN.write_text(main)

gradle = GRADLE.read_text()
gradle = replace_once(gradle, 'versionCode = 114', 'versionCode = 115', 'versionCode')
gradle = replace_once(gradle, 'versionName = "5.19.15"', 'versionName = "5.19.16"', 'versionName')
GRADLE.write_text(gradle)

workflow = ANDROID_YML.read_text()
workflow = workflow.replace('pi-android-v5.19.15-apks', 'pi-android-v5.19.16-apks')
ANDROID_YML.write_text(workflow)

print("v5.19.16 source repair applied")
