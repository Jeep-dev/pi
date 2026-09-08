from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")

# 1) Make the slash command palette genuinely touch-scrollable. Do not truncate
# the command set to the first 12 items; keep a bounded viewport and let the user
# swipe vertically through all matching commands.
old_palette = r'''@Composable
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
    }.take(12)
    if (choices.isEmpty()) return
    Column(
        modifier
            .padding(10.dp)
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Text("Pi Commands", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 8.dp))
        choices.forEach { (cmd, remote) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(cmd.name, remote) }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("/${cmd.name}", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(104.dp))
                Text(cmd.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
'''

new_palette = r'''@Composable
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
    }.take(64)
    if (choices.isEmpty()) return
    val paletteState = rememberLazyListState()
    Column(
        modifier
            .padding(10.dp)
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pi Commands", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("上下滑动选择", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        LazyColumn(
            state = paletteState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentPadding = PaddingValues(bottom = 4.dp)
        ) {
            items(choices) { choice ->
                val cmd = choice.first
                val remote = choice.second
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(cmd.name, remote) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("/${cmd.name}", color = Blue, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(104.dp))
                    Text(cmd.description, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
'''
replace_once(main, old_palette, new_palette)

# 2) Put reasoning_effort directly inside /model. Pi's public RPC accepts the
# exact thinking levels low/medium/high/xhigh/max through set_thinking_level;
# Pi then maps those levels through each model's thinkingLevelMap to the actual
# provider reasoning_effort value. Keep the model screen open after changes so
# a model and its effort can be selected in one visit.
old_call = r'''                Panel.Models -> ModelsPanel(models, currentState, onBack = { panel = Panel.Chat }) { model ->
                    scope.launch {
                        bridge.setModel(model).fold(
                            onSuccess = { refreshMeta(); panel = Panel.Chat; addSystem("模型已切换为 ${model.provider}/${model.id}") },
                            onFailure = { addSystem("切换模型失败：${it.message}") }
                        )
                    }
                }
'''

new_call = r'''                Panel.Models -> ModelsPanel(
                    models = models,
                    state = currentState,
                    onBack = { panel = Panel.Chat },
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
'''
replace_once(main, old_call, new_call)

old_models = r'''@Composable
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
'''

new_models = r'''@Composable
private fun ModelsPanel(
    models: List<PiModel>,
    state: PiState?,
    onBack: () -> Unit,
    onPick: (PiModel) -> Unit,
    onEffort: (String) -> Unit
) {
    val effortLevels = listOf("low", "medium", "high", "xhigh", "max")
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
                Text("Models", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            }
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
'''
replace_once(main, old_models, new_models)

print("Applied PiTouch v4.6 command scroll + reasoning effort patch")
