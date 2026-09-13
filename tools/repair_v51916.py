from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"
TEST = ROOT / "tools/test_active_session_ui.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text()

if "val collapseInfo = when" not in main:
    main = replace_once(
        main,
        '''                        val expandable = argsHint.isNotBlank() || outputHint.isNotBlank()
                        Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(3.dp)).padding(horizontal = 9.dp, vertical = 8.dp)) {''',
        '''                        val expandable = argsHint.isNotBlank() || outputHint.isNotBlank()
                        val collapseInfo = when {
                            line.collapsed && outputHint.isNotBlank() -> outputHint
                            line.collapsed && argsHint.isNotBlank() -> argsHint
                            toolOutput.isNotBlank() -> "${toolOutput.trimEnd().lines().size} lines"
                            line.toolArgs.isNotBlank() -> "${line.toolArgs.trimEnd().lines().size} lines"
                            else -> ""
                        }
                        Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(3.dp)).padding(horizontal = 9.dp, vertical = 8.dp)) {''',
        "tool collapse info",
    )

if "contentAlignment = Alignment.CenterStart" not in main:
    main = replace_once(
        main,
        '''                            if (expandable) {
                                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onToggleLine(lineIndex) }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text(if (line.collapsed) "Show all" else "Collapse", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                                    Spacer(Modifier.weight(1f))
                                    if (duration.isNotBlank()) Text(duration, color = colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                                }
                            } else if (duration.isNotBlank()) {''',
        '''                            if (expandable) {
                                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        if (collapseInfo.isNotBlank()) Text(collapseInfo, color = colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                                    }
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        TextButton(
                                            onClick = { onToggleLine(lineIndex) },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                                        ) { Text(if (line.collapsed) "Show all" else "Collapse", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                                    }
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                        if (duration.isNotBlank()) Text(duration, color = colors.toolMeta, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                                    }
                                }
                            } else if (duration.isNotBlank()) {''',
        "centered show-all footer",
    )

if "工具卡片底栏：左侧折叠行数" not in main:
    main = main.replace(
        '"""Pi Android v5.19.20\n                |• 修复 web search / 工具结束后 Compose 延迟重排导致的偶发自动跟随失效',
        '"""Pi Android v5.19.20\n                |• 工具卡片底栏：左侧折叠行数 · 中间 Show all / Collapse · 右侧执行时间\n                |• 修复 web search / 工具结束后 Compose 延迟重排导致的偶发自动跟随失效',
        1,
    )
MAIN.write_text(main)

test = TEST.read_text()
if "tool footer must show hidden-line metadata on the left" not in test:
    test += '\nassert.ok(main.includes("val collapseInfo = when"), "tool footer must derive Pi-style hidden-line metadata");\n'
    test += 'assert.ok(main.includes("contentAlignment = Alignment.CenterStart"), "tool footer must show hidden-line metadata on the left");\n'
    test += 'assert.ok(main.includes("contentAlignment = Alignment.CenterEnd"), "tool footer must keep execution duration on the right");\n'
TEST.write_text(test)

print("centered Pi-style tool footer repair applied")
