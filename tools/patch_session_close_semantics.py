from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"anchor count for {path} is {count}, expected 1")
    p.write_text(text.replace(old, new, 1))


bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
text = bridge.read_text()
start_marker = "    /** Stop only this endpoint and remove only its private bridge/session namespace. */\n"
end_marker = "    suspend fun installAndStartBridge(): Result<Unit>"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("PiBridge close block anchors not found")
replacement = '''    /** Close this Android Session like a terminal tab: stop its runtime and keep all files/history. */
    suspend fun shutdownRuntime(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request("/shutdown", "{}", 5_000).getOrThrow()
            delay(750)
            // Deliberately keep bridge files, session directories, JSONL history,
            // endpoint tokens, and runtime preferences. Closing a tab is not deletion.
        }
    }

'''
bridge.write_text(text[:start] + replacement + text[end:])

runtime = "app/src/main/java/com/piandroid/PiSessionRuntime.kt"
replace_once(runtime,
'''    fun closeAndCleanup(ownedSessionFile: String): Job {
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
''',
'''    fun closeRuntime(): Job {
        val closedNow = fenceClient()
        if (!closedNow) return scope.launch { }
        return scope.launch {
            try {
                bridge.shutdownRuntime()
            } finally {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
''')
replace_once(runtime,
    '        return runtime.closeAndCleanup(record.ownedSessionFile)',
    '        return runtime.closeRuntime()')

main = "app/src/main/java/com/piandroid/MainActivity.kt"
replace_once(main, 'Text("删除", color = Danger)', 'Text("关闭", color = Danger)')
replace_once(main, 'title = { Text("删除 Pi Session？") }', 'title = { Text("关闭 Pi Session？") }')
replace_once(main,
    'text = { Text("将停止并清理“${sessionDisplayName(record)}”自己的 Pi、Bridge 和会话配置；不会删除项目文件或其他 Session。") }',
    'text = { Text("将关闭“${sessionDisplayName(record)}”的 Pi/Bridge，并从 Pi Sessions 列表移除；不会删除任何 Pi 会话历史或项目文件。") }')
replace_once(main, 'Text("确认删除", color = Danger)', 'Text("确认关闭", color = Danger)')

Path("tools/test_session_close_preserves_history.mjs").write_text('''import assert from "node:assert/strict";\nimport { readFileSync } from "node:fs";\n\nconst bridge = readFileSync("app/src/main/java/com/piandroid/PiBridge.kt", "utf8");\nconst runtime = readFileSync("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");\nconst main = readFileSync("app/src/main/java/com/piandroid/MainActivity.kt", "utf8");\n\nconst start = bridge.indexOf("suspend fun shutdownRuntime()");\nconst end = bridge.indexOf("suspend fun installAndStartBridge", start);\nassert.ok(start >= 0 && end > start, "shutdownRuntime block must exist");\nconst closeBlock = bridge.slice(start, end);\nassert.match(closeBlock, /request\\("\\/shutdown"/);\nassert.doesNotMatch(closeBlock, /rm\\s+-/);\nassert.doesNotMatch(closeBlock, /unlink/);\nassert.doesNotMatch(closeBlock, /forgetEndpointToken/);\nassert.doesNotMatch(closeBlock, /runtimePreferences\\(\\)\\.edit/);\nassert.match(runtime, /bridge\\.shutdownRuntime\\(\\)/);\nassert.doesNotMatch(runtime, /shutdownAndCleanup/);\nassert.match(main, /不会删除任何 Pi 会话历史或项目文件/);\nconsole.log("session close preserves Pi history: ok");\n''')

gradle = "app/build.gradle.kts"
replace_once(gradle,
    '// v5.19.24 build 124: share /resume discovery across Android Sessions with the same cwd.',
    '// v5.19.25 build 125: closing an Android Session preserves all Pi history and on-disk data.')
replace_once(gradle, '        versionCode = 124', '        versionCode = 125')
replace_once(gradle, '        versionName = "5.19.24"', '        versionName = "5.19.25"')

workflow = Path(".github/workflows/android.yml")
w = workflow.read_text()
if 'node tools/test_session_close_preserves_history.mjs' not in w:
    w = w.replace('          node tools/test_active_session_ui.mjs\n', '          node tools/test_active_session_ui.mjs\n          node tools/test_session_close_preserves_history.mjs\n', 1)
w = w.replace('          name: pi-android-v5.19.24-apks', '          name: pi-android-v5.19.25-apks', 1)
w = w.replace('# v5.19.24 same-cwd shared /resume discovery validation', '# v5.19.25 terminal-style Session close validation', 1)
workflow.write_text(w)
