from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:220]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")

# Remove the fixed, boxed terminal header. The path/model/status now live as the
# first item inside the message LazyColumn, so they naturally scroll away and
# can be covered by later conversation content.
old_header_call = r'''        TerminalHeader(
            cwd = cwd,
            model = currentState?.let { it.modelName.ifBlank { it.modelId } }.orEmpty(),
            status = status,
            connected = connected,
            onConnect = connect,
            onSettings = { panel = Panel.Settings }
        )

'''
replace_once(main, old_header_call, "")

replace_once(
    main,
    '                Panel.Chat -> ChatPanel(lines, chatListState)\n',
    '''                Panel.Chat -> ChatPanel(
                    lines = lines,
                    listState = chatListState,
                    cwd = cwd,
                    model = currentState?.let { it.modelName.ifBlank { it.modelId } }.orEmpty(),
                    status = status,
                    connected = connected,
                    onConnect = connect,
                    onSettings = { panel = Panel.Settings }
                )
''',
)

old_chat = r'''@Composable
private fun ChatPanel(lines: List<ChatLine>, listState: LazyListState) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
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
                "thinking" -> Text(
                    line.text,
                    color = ThinkingText,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)
                )
                "tool" -> Text(
                    line.text,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(ToolBg, RoundedCornerShape(3.dp)).padding(12.dp)
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
'''

new_chat = r'''@Composable
private fun ChatPanel(
    lines: List<ChatLine>,
    listState: LazyListState,
    cwd: String,
    model: String,
    status: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onSettings: () -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "session-meta") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
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
                        if (connected) "⋮" else "Connect",
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
                "thinking" -> Text(
                    line.text,
                    color = ThinkingText,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)
                )
                "tool" -> Text(
                    line.text,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(ToolBg, RoundedCornerShape(3.dp)).padding(12.dp)
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
'''
replace_once(main, old_chat, new_chat)

print("Applied PiTouch v4.8 immersive scroll-away header patch")
