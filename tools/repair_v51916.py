from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"
MARKDOWN = ROOT / "app/src/main/java/com/piandroid/MarkdownContent.kt"
BUILD = ROOT / "app/build.gradle.kts"
ANDROID = ROOT / ".github/workflows/android.yml"
TEST = ROOT / "tools/test_active_session_ui.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def replace_first(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: no match")
    return text.replace(old, new, 1)


main = MAIN.read_text()
if "Pi Android v5.19.18" not in main:
    main = replace_first(
        main,
        "val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)",
        "val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)",
        "drawer first-down pass",
    )
    main = replace_once(
        main,
        "val event = awaitPointerEvent(PointerEventPass.Initial)\n                        val change = event.changes.firstOrNull { it.id == down.id } ?: break\n                        velocityTracker.addPosition(change.uptimeMillis, change.position)",
        "val event = awaitPointerEvent(PointerEventPass.Final)\n                        val change = event.changes.firstOrNull { it.id == down.id } ?: break\n                        // Horizontal child surfaces (Markdown tables/code blocks, attachment rows, etc.)\n                        // get first refusal. The Session drawer only owns an unconsumed horizontal drag.\n                        if (startProgress <= 0.01f && change.isConsumed) return@awaitEachGesture\n                        velocityTracker.addPosition(change.uptimeMillis, change.position)",
        "drawer child-consumption guard",
    )
    main = replace_once(
        main,
        '"""Pi Android v5.19.17\n                |• /settings 显示并可编辑每个 Session 的附加启动参数',
        '"""Pi Android v5.19.18\n                |• Markdown 表格和代码块优先接管横向滑动，不再误触 Session 侧栏\n                |• 宽表格使用完整屏幕宽度作为横向滚动视口，可左右查看全部列\n                |• /settings 显示并可编辑每个 Session 的附加启动参数',
        "changelog version",
    )
MAIN.write_text(main)

markdown = MARKDOWN.read_text()
if "Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border" not in markdown:
    markdown = replace_once(
        markdown,
        "Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, colors.markdownBorder)) {",
        "Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, colors.markdownBorder)) {",
        "table horizontal viewport",
    )
MARKDOWN.write_text(markdown)

build = BUILD.read_text()
build = build.replace("// v5.19.17: expose editable per-Session startup arguments in /settings.", "// v5.19.18: reserve horizontal child scrolling before the Session drawer gesture.")
build = build.replace("versionCode = 116", "versionCode = 117")
build = build.replace('versionName = "5.19.17"', 'versionName = "5.19.18"')
BUILD.write_text(build)

android = ANDROID.read_text().replace("pi-android-v5.19.17-apks", "pi-android-v5.19.18-apks")
ANDROID.write_text(android)

test = TEST.read_text()
if "MarkdownContent.kt" not in test:
    test = test.replace(
        'const runtime = await readFile("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");\n',
        'const runtime = await readFile("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");\nconst markdown = await readFile("app/src/main/java/com/piandroid/MarkdownContent.kt", "utf8");\n',
    )
if "Session drawer must wait for horizontal child scroll surfaces" not in test:
    test += '\nassert.ok(main.includes("awaitPointerEvent(PointerEventPass.Final)"), "Session drawer must wait for horizontal child scroll surfaces");\n'
    test += 'assert.ok(main.includes("change.isConsumed"), "consumed child drags must not open the Session drawer");\n'
    test += 'assert.ok(markdown.includes("Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())"), "wide Markdown tables need a full-width horizontal viewport");\n'
TEST.write_text(test)

print("v5.19.18 table-scroll gesture repair applied")
