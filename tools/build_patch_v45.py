from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")
pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
extras = Path("app/src/main/java/com/piandroid/PiBridgeExtras.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")

# v4.5 deliberately keeps the proven v4.4 plain `pi --mode rpc` startup.
# Resume is implemented outside Pi extensions: Android lists session files via
# the bridge and switches them with Pi's native switch_session RPC.
replace_once(pi_bridge, "private val port = 17644", "private val port = 17645")
replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.4"',
    'private val expectedBridgeVersion = "2026-09-09.5"',
)

replace_once(bridge, "PI_ANDROID_PORT || 17644", "PI_ANDROID_PORT || 17645")
replace_once(bridge, 'const bridgeVersion = "2026-09-09.4";', 'const bridgeVersion = "2026-09-09.5";')
replace_once(
    bridge,
    'import { readdir, readFile, writeFile } from "node:fs/promises";',
    'import { readdir, readFile, writeFile, stat, open } from "node:fs/promises";',
)

session_helpers = r'''
function sessionDirForCwd(baseCwd) {
  const resolved = path.resolve(baseCwd);
  const safe = `--${resolved.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--`;
  return path.join(termuxHome, ".pi", "agent", "sessions", safe);
}

function visibleText(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .filter(part => part && part.type === "text")
    .map(part => String(part.text || ""))
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

async function readSlice(handle, offset, length) {
  if (length <= 0) return "";
  const buffer = Buffer.alloc(length);
  const result = await handle.read(buffer, 0, length, offset);
  return buffer.subarray(0, result.bytesRead).toString("utf8");
}

async function summarizeSession(file, currentFile) {
  const info = await stat(file);
  const handle = await open(file, "r");
  try {
    const chunk = 256 * 1024;
    const headLen = Math.min(info.size, chunk);
    const tailLen = Math.min(info.size, chunk);
    const head = await readSlice(handle, 0, headLen);
    const tailOffset = Math.max(0, info.size - tailLen);
    const tail = tailOffset > 0 ? await readSlice(handle, tailOffset, tailLen) : "";

    let id = "";
    let firstUser = "";
    for (const line of head.split("\n")) {
      if (!line.trim()) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }
      if (!id && entry?.type === "session") id = String(entry.id || "");
      if (!firstUser && entry?.type === "message" && entry?.message?.role === "user") {
        firstUser = visibleText(entry.message.content);
      }
      if (id && firstUser) break;
    }

    let name = "";
    const tailLines = (head + "\n" + tail).split("\n");
    for (const line of tailLines) {
      if (!line.trim()) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }
      if (entry?.type === "session_info") name = String(entry.name || "").trim();
    }

    const title = name || firstUser || path.basename(file, ".jsonl");
    return {
      path: file,
      id,
      title: title.length > 120 ? title.slice(0, 120) + "…" : title,
      modified: info.mtimeMs,
      current: !!currentFile && path.resolve(file) === path.resolve(currentFile),
    };
  } finally {
    await handle.close();
  }
}

async function listSessions() {
  const dir = sessionDirForCwd(cwd);
  let names;
  try { names = await readdir(dir); }
  catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }

  let currentFile = "";
  try {
    const state = await rpc({ type: "get_state" }, 8000);
    currentFile = String(state?.data?.sessionFile || "");
  } catch {}

  const files = [];
  for (const name of names) {
    if (!name.endsWith(".jsonl")) continue;
    const file = path.join(dir, name);
    try {
      const info = await stat(file);
      files.push({ file, modified: info.mtimeMs });
    } catch {}
  }
  files.sort((a, b) => b.modified - a.modified);

  const summaries = [];
  for (const item of files.slice(0, 80)) {
    try { summaries.push(await summarizeSession(item.file, currentFile)); }
    catch {}
  }
  return summaries;
}
'''
replace_once(bridge, "function safePath(relativePath = \"\") {", session_helpers + "\nfunction safePath(relativePath = \"\") {")

session_routes = r'''
    if (req.method === "GET" && url.pathname === "/sessions") {
      const sessions = await listSessions();
      return send(res, 200, { sessions });
    }

    if (req.method === "POST" && url.pathname === "/switch-session") {
      const input = JSON.parse(await readBody(req) || "{}");
      const target = path.resolve(String(input.path || ""));
      const dir = path.resolve(sessionDirForCwd(cwd));
      if (!target.endsWith(".jsonl") || (target !== dir && !target.startsWith(`${dir}${path.sep}`))) {
        throw new Error("session path outside current project");
      }
      if (!existsSync(target)) throw new Error("session file not found");
      const result = await rpc({ type: "switch_session", sessionPath: target }, 30000);
      const state = await rpc({ type: "get_state" }, 10000);
      return send(res, 200, { ok: true, result: result.data || null, state: state.data || null });
    }
'''
replace_once(
    bridge,
    '    if (req.method === "GET" && url.pathname === "/messages") return rpcResponse(res, { type: "get_messages" });\n',
    '    if (req.method === "GET" && url.pathname === "/messages") return rpcResponse(res, { type: "get_messages" });\n' + session_routes,
)

# All helper HTTP calls must use the same fresh bridge port; v4.4 accidentally left
# history() pointed at 17643, which could mix data from a stale bridge.
replace_once(extras, 'URL("http://127.0.0.1:17643/messages")', 'URL("http://127.0.0.1:17645/messages")')

extras_append = r'''

suspend fun PiBridge.sessions(): Result<List<PiSession>> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("http://127.0.0.1:17645/sessions").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 12000
            useCaches = false
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $raw")
        val array = JSONObject(raw).optJSONArray("sessions") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    PiSession(
                        path = item.optString("path"),
                        id = item.optString("id"),
                        title = item.optString("title").ifBlank { "未命名 session" },
                        modified = item.optLong("modified"),
                        current = item.optBoolean("current")
                    )
                )
            }
        }
    }
}

suspend fun PiBridge.switchSession(sessionPath: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("http://127.0.0.1:17645/switch-session").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 35000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("path", sessionPath).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $raw")
    }
}

data class PiSession(
    val path: String,
    val id: String,
    val title: String,
    val modified: Long,
    val current: Boolean
)
'''
extras.write_text(extras.read_text(encoding="utf-8") + extras_append, encoding="utf-8")

# Android state + real /resume action.
replace_once(
    main,
    '    var dialogInput by remember { mutableStateOf("") }\n',
    '    var dialogInput by remember { mutableStateOf("") }\n    var resumeSessions by remember { mutableStateOf<List<PiSession>>(emptyList()) }\n    var resumeOpen by remember { mutableStateOf(false) }\n',
)
replace_once(
    main,
    '            "/resume", "/tree", "/fork", "/name" -> sendExtensionCommand(text)\n',
    '''            "/resume" -> scope.launch {
                bridge.sessions().fold(
                    onSuccess = { sessions ->
                        resumeSessions = sessions
                        resumeOpen = true
                    },
                    onFailure = { addSystem("/resume 失败：${it.message}") }
                )
            }
            "/tree", "/fork", "/name" -> addSystem("$command 尚未接入当前无扩展 RPC 版本，避免把命令误发给模型。")
''',
)

resume_dialog = r'''
    if (resumeOpen) {
        AlertDialog(
            onDismissRequest = { resumeOpen = false },
            title = { Text("/resume · 选择会话") },
            text = {
                if (resumeSessions.isEmpty()) {
                    Text("当前工作目录没有可恢复的 Pi session。", color = TextMuted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 500.dp)) {
                        items(resumeSessions) { session ->
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

'''
replace_once(main, "    val busy = connected && (\n", resume_dialog + "    val busy = connected && (\n")

print("Applied PiTouch v4.5 direct resume patch")
