import http from "node:http";
import { spawn, execFile } from "node:child_process";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { StringDecoder } from "node:string_decoder";
import { createWriteStream, existsSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { lstat, mkdir, open, readdir, readFile, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { promisify } from "node:util";

const port = Number(process.env.PI_ANDROID_PORT || 17649);
const bridgeVersion = "2026-09-11.16";
const bridgeCapabilities = [
  "file-reference-v1",
  "stream-upload-v1",
  "long-compact-v1",
  "durable-history-v1",
  "recovery-snapshot-v1",
  "persistent-widgets-v1",
  "consistent-recovery-v1",
  "bounded-event-cache-v1",
];
const authToken = process.env.PI_ANDROID_TOKEN || "";
if (authToken.length < 32) throw new Error("PI_ANDROID_TOKEN is required");
const execFileAsync = promisify(execFile);
const termuxPrefix = process.env.PREFIX || "/data/data/com.termux/files/usr";
const termuxHome = process.env.HOME || "/data/data/com.termux/files/home";
const termuxBin = path.join(termuxPrefix, "bin");
const termuxBash = path.join(termuxBin, "bash");
const termuxPi = path.join(termuxBin, "pi");
const nodeExecutable = process.execPath || path.join(termuxBin, "node");
const pidFile = path.join(termuxHome, ".pi", "android", "bridge.pid");
const maxRequestBytes = 4_000_000;
const maxUploadBytes = 128 * 1024 * 1024;
let child = null;
let cwd = termuxHome;
let launchCommand = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts";
let activeSessionFile = "";
let sequence = 0;
let lastStderr = "";
let lastStdoutTail = "";
let lastExit = null;
const events = [];
const maxEvents = 5000;
const configuredEventBytes = Number(process.env.PI_ANDROID_MAX_EVENT_BYTES || 24 * 1024 * 1024);
const maxEventBytes = Number.isFinite(configuredEventBytes) ? Math.max(256 * 1024, configuredEventBytes) : 24 * 1024 * 1024;
let eventBytes = 0;
const responseEventSequence = Symbol("responseEventSequence");
const pending = new Map();
const eventWaiters = new Set();
const pendingUiRequests = new Map();
const persistentUiEvents = new Map();
let latestQueueEvent = null;

function addEvent(value) {
  const event = { seq: ++sequence, receivedAt: Date.now(), value };
  const byteSize = Buffer.byteLength(JSON.stringify(value));
  Object.defineProperty(event, "byteSize", { value: byteSize });
  events.push(event);
  eventBytes += byteSize;
  while (events.length > 1 && (events.length > maxEvents || eventBytes > maxEventBytes)) {
    eventBytes -= events.shift().byteSize;
  }
  if (value?.type === "queue_update") latestQueueEvent = event;
  if (value?.type === "agent_settled" || value?.type === "process_exit" || value?.type === "process_reset") latestQueueEvent = null;
  if (value?.type === "extension_ui_request" && ["select", "confirm", "input", "editor"].includes(value.method) && value.id) {
    pendingUiRequests.set(String(value.id), value);
  }
  if (value?.type === "extension_ui_request" && value.method === "setWidget" && value.widgetKey) {
    const key = String(value.widgetKey);
    if (Array.isArray(value.widgetLines)) persistentUiEvents.set(key, event);
    else persistentUiEvents.delete(key);
  }
  for (const wake of eventWaiters) wake();
  eventWaiters.clear();
}

function waitForEvent(after, timeoutMs) {
  if (sequence > after) return Promise.resolve();
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      eventWaiters.delete(wake);
      resolve();
    }, timeoutMs);
    timer.unref?.();
    const wake = () => {
      clearTimeout(timer);
      resolve();
    };
    eventWaiters.add(wake);
  });
}

function settlePending(id, value) {
  const item = pending.get(id);
  if (!item) return false;
  pending.delete(id);
  clearTimeout(item.timer);
  value[responseEventSequence] = sequence;
  const responseCommand = String(value.command || item.command || "");
  if (value.success !== false && responseCommand === "get_state") {
    const sessionFile = String(value?.data?.sessionFile || "");
    if (sessionFile) activeSessionFile = sessionFile;
  }
  if (value.success === false) item.reject(new Error(value.error || `${responseCommand || "RPC"} failed`));
  else item.resolve(value);
  return true;
}

function attachJsonl(stream) {
  const decoder = new StringDecoder("utf8");
  let buffer = "";
  stream.on("data", chunk => {
    buffer += decoder.write(chunk);
    while (true) {
      const index = buffer.indexOf("\n");
      if (index < 0) break;
      let line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (!line.trim()) continue;
      lastStdoutTail = (lastStdoutTail + line + "\n").slice(-8000);
      try {
        const value = JSON.parse(line);
        if (value.type === "response" && value.id && settlePending(value.id, value)) continue;
        addEvent(value);
      } catch {
        addEvent({ type: "raw", line });
      }
    }
  });
  stream.on("end", () => {
    const rest = buffer + decoder.end();
    if (rest.trim()) {
      lastStdoutTail = (lastStdoutTail + rest + "\n").slice(-8000);
      try {
        const value = JSON.parse(rest);
        if (value.type === "response" && value.id && settlePending(value.id, value)) return;
        addEvent(value);
      } catch { addEvent({ type: "raw", line: rest }); }
    }
  });
}

function stopPi() {
  addEvent({ type: "process_reset" });
  pendingUiRequests.clear();
  persistentUiEvents.clear();
  if (child && child.exitCode == null) {
    try { child.kill("SIGTERM"); } catch {}
  }
  child = null;
  for (const [, item] of pending) {
    clearTimeout(item.timer);
    item.reject(new Error("Pi process stopped"));
  }
  pending.clear();
}

function termuxEnvironment() {
  const currentPath = process.env.PATH || "";
  const requiredPath = [termuxBin, path.join(termuxBin, "applets")].filter(Boolean).join(":");
  return {
    ...process.env,
    PREFIX: termuxPrefix,
    HOME: termuxHome,
    TMPDIR: process.env.TMPDIR || path.join(termuxPrefix, "tmp"),
    PATH: currentPath.includes(termuxBin) ? currentPath : `${requiredPath}:${currentPath}`,
  };
}

function expandHome(value) {
  if (value === "~") return termuxHome;
  if (value.startsWith("~/")) return path.join(termuxHome, value.slice(2));
  return value;
}

function splitCommand(command) {
  const parts = [];
  let current = "";
  let quote = null;
  let escaped = false;

  for (const ch of command) {
    if (escaped) {
      current += ch;
      escaped = false;
      continue;
    }
    if (ch === "\\" && quote !== "'") {
      escaped = true;
      continue;
    }
    if (quote) {
      if (ch === quote) quote = null;
      else current += ch;
      continue;
    }
    if (ch === "'" || ch === '"') {
      quote = ch;
      continue;
    }
    if (/\s/.test(ch)) {
      if (current) {
        parts.push(current);
        current = "";
      }
      continue;
    }
    current += ch;
  }
  if (escaped) current += "\\";
  if (quote) throw new Error("Pi launch command has an unterminated quote");
  if (current) parts.push(current);
  return parts;
}

function spawnPi(nextLaunchCommand, nextCwd) {
  const parts = splitCommand(nextLaunchCommand);
  if (!parts.length) throw new Error("Pi launch command is empty");

  const executable = expandHome(parts[0]);
  const args = parts.slice(1).map(expandHome);
  const isPiCommand = executable === "pi" || executable === termuxPi || path.basename(executable) === "pi";
  const env = termuxEnvironment();

  if (isPiCommand) {
    const piScript = executable === "pi" ? termuxPi : executable;
    if (!existsSync(nodeExecutable)) throw new Error(`Termux Node not found: ${nodeExecutable}`);
    if (!existsSync(piScript)) throw new Error(`Pi executable not found: ${piScript}`);

    addEvent({
      type: "launcher_info",
      text: `Starting Pi with Node directly: ${nodeExecutable} ${piScript}`,
    });

    return spawn(nodeExecutable, [piScript, ...args], {
      cwd: nextCwd,
      env,
      stdio: ["pipe", "pipe", "pipe"],
    });
  }

  if (!existsSync(termuxBash)) throw new Error(`Termux bash not found: ${termuxBash}`);
  addEvent({ type: "launcher_info", text: `Starting custom command with Termux bash: ${nextLaunchCommand}` });
  return spawn(termuxBash, ["-lc", nextLaunchCommand], {
    cwd: nextCwd,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
}

async function startPi(nextCwd, nextLaunchCommand) {
  stopPi();
  cwd = expandHome((nextCwd || cwd).trim()) || termuxHome;
  launchCommand = (nextLaunchCommand || launchCommand).trim();
  if (!launchCommand) throw new Error("Pi launch command is empty");
  lastStderr = "";
  lastStdoutTail = "";
  lastExit = null;

  let startedChild;
  try {
    startedChild = spawnPi(launchCommand, cwd);
    child = startedChild;
  } catch (error) {
    throw new Error(`Pi launcher failed: ${String(error?.message || error)}`);
  }

  attachJsonl(startedChild.stdout);
  startedChild.stderr.setEncoding("utf8");
  startedChild.stderr.on("data", text => {
    lastStderr = (lastStderr + text).slice(-8000);
    addEvent({ type: "stderr", text });
  });
  startedChild.on("error", error => {
    if (child !== startedChild) return;
    lastStderr = (lastStderr + `\n${error.message}`).slice(-8000);
    addEvent({ type: "stderr", text: error.message });
  });
  startedChild.on("exit", (code, signal) => {
    if (child !== startedChild) return;
    lastExit = { code, signal };
    addEvent({ type: "process_exit", code, signal, stderr: lastStderr, stdout: lastStdoutTail });
    for (const [, item] of pending) {
      clearTimeout(item.timer);
      item.reject(new Error(`Pi exited (${code ?? signal ?? "unknown"})${lastStderr ? `: ${lastStderr.trim()}` : ""}`));
    }
    pending.clear();
    child = null;
  });
  await new Promise(resolve => setTimeout(resolve, 450));
  if (child !== startedChild || startedChild.exitCode != null) {
    const exitText = lastExit ? `exit=${lastExit.code ?? "?"} signal=${lastExit.signal ?? "-"}` : "exit=unknown";
    const details = [lastStderr.trim(), lastStdoutTail.trim()].filter(Boolean).join("\n--- stdout ---\n");
    throw new Error(`Pi failed to start (${exitText})${details ? `\n${details}` : ""}`);
  }
}

function sendRaw(value) {
  if (!child || child.exitCode != null || !child.stdin.writable) throw new Error("Pi is not running");
  child.stdin.write(JSON.stringify(value) + "\n");
}

function rpc(command, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    if (!child || child.exitCode != null || !child.stdin.writable) return reject(new Error("Pi is not running"));
    const id = command.id || randomUUID();
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`RPC timeout: ${command.type}`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer, command: command.type });
    try {
      sendRaw({ ...command, id });
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(error);
    }
  });
}


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

function contentText(content, imageLabel = "[图片]") {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  const parts = [];
  for (const part of content) {
    if (!part || typeof part !== "object") continue;
    if (part.type === "text" && part.text) parts.push(String(part.text));
    else if (part.type === "image") parts.push(imageLabel);
  }
  return parts.join("\n");
}

function toolArgumentsText(name, args) {
  if (!args || typeof args !== "object") return "";
  if (name === "write") {
    const content = String(args.content || "");
    const preview = content.length > 1600 ? content.slice(-1600) : content;
    return [`目标：${String(args.path || "")}`, `内容：${content.length} 字符`, preview ? `写入预览${content.length > preview.length ? "（末尾）" : ""}：\n${preview}` : ""].filter(Boolean).join("\n\n");
  }
  if (name === "edit") return `目标：${String(args.path || "")}\n修改块：${Array.isArray(args.edits) ? args.edits.length : 0}`;
  if (name === "bash") return `命令：${String(args.command || "")}`;
  const formatted = JSON.stringify(args, null, 2);
  return formatted.length > 4000 ? `${formatted.slice(0, 4000)}\n… 参数显示已截断` : formatted;
}

function toolResultText(name, message) {
  const output = contentText(message?.content, "[图片输出]");
  const isError = Boolean(message?.isError);
  const sections = [isError ? `工具执行失败：${name}` : `工具完成：${name}`];
  const diff = !isError && name === "edit" ? String(message?.details?.diff || "") : "";
  if (diff.trim() && !output.includes(diff)) sections.push(diff);
  if (output.trim()) sections.push(output);
  return sections.join("\n\n");
}

function historyFromEntries(data) {
  const entries = Array.isArray(data?.entries) ? data.entries : [];
  const byId = new Map(entries.filter(entry => entry?.id).map(entry => [String(entry.id), entry]));
  let branch = [];
  let id = data?.leafId == null ? null : String(data.leafId);
  const seen = new Set();
  while (id && !seen.has(id)) {
    seen.add(id);
    const entry = byId.get(id);
    if (!entry) break;
    branch.push(entry);
    id = entry.parentId == null ? null : String(entry.parentId);
  }
  branch.reverse();

  // get_entries intentionally returns the append-only session, including
  // messages that a compaction has replaced in the model context. Project the
  // active path the same way Pi's buildContextEntries() does: the newest
  // compaction summary, its retained tail, and entries created afterwards.
  const compactionIndex = branch.findLastIndex(entry => entry?.type === "compaction");
  if (compactionIndex >= 0) {
    const compaction = branch[compactionIndex];
    let retained = [];
    if (Array.isArray(compaction.retainedTail)) {
      retained = compaction.retainedTail.map((message, index) => ({
        type: "message",
        id: `${String(compaction.id || "compaction")}:retained:${index}`,
        message,
      }));
    } else {
      const firstKeptId = String(compaction.firstKeptEntryId || "");
      const firstKeptIndex = firstKeptId
        ? branch.findIndex((entry, index) => index < compactionIndex && String(entry?.id || "") === firstKeptId)
        : -1;
      if (firstKeptIndex >= 0) retained = branch.slice(firstKeptIndex, compactionIndex);
    }
    branch = [compaction, ...retained, ...branch.slice(compactionIndex + 1)];
  }

  const history = [];
  const toolLines = new Map();
  const add = (role, text, toolCallId = "", collapsed = false, extra = {}) => {
    if (!String(text || "").trim()) return;
    history.push({ role, text: String(text), toolCallId, collapsed, ...extra });
  };

  for (const entry of branch) {
    if (entry?.type === "message") {
      const message = entry.message || {};
      const role = String(message.role || "");
      if (role === "user") {
        add("user", contentText(message.content, "[图片附件]"));
      } else if (role === "assistant") {
        const content = Array.isArray(message.content) ? message.content : [{ type: "text", text: String(message.content || "") }];
        for (const part of content) {
          if (part?.type === "thinking") add("thinking", String(part.thinking || part.text || ""));
          else if (part?.type === "text") add("assistant", String(part.text || ""));
          else if (part?.type === "toolCall") {
            const callId = String(part.id || "");
            const name = String(part.name || "tool");
            const details = toolArgumentsText(name, part.arguments);
            add("tool", `执行工具：${name}${details ? `\n\n${details}` : ""}`, callId, true);
            if (callId) toolLines.set(callId, history.length - 1);
          }
        }
        if (message.stopReason === "aborted" && !content.some(part => part?.type === "text" && String(part.text || "").trim())) {
          add("system", "本轮任务已中止");
        } else if (message.stopReason === "error" && message.errorMessage) {
          add("system", `模型错误：${String(message.errorMessage)}`);
        }
      } else if (role === "toolResult") {
        const callId = String(message.toolCallId || "");
        const name = String(message.toolName || "tool");
        const result = toolResultText(name, message);
        const index = toolLines.get(callId);
        if (index != null && history[index]) history[index].text += `\n\n${result}`;
        else add("tool", result, callId, true);
      } else if (role === "bashExecution") {
        const output = contentText(message.content) || String(message.output || "");
        add("tool", `执行 Bash：${String(message.command || "")}${output ? `\n\n${output}` : ""}`, String(message.toolCallId || entry.id || ""), true);
      }
    } else if (entry?.type === "compaction") {
      add(
        "compaction",
        String(entry.summary || "摘要不可用"),
        "",
        true,
        { tokensBefore: Number(entry.tokensBefore || 0) },
      );
    } else if (entry?.type === "branch_summary") {
      add("system", `分支摘要：${String(entry.summary || "")}`);
    } else if (entry?.type === "custom_message" && !String(entry.customType || "").startsWith("__android_")) {
      add("system", contentText(entry.content));
    }
  }
  return history;
}

async function durableHistory() {
  const response = await rpc({ type: "get_entries" }, 60_000);
  const data = response?.data || {};
  const entries = Array.isArray(data.entries) ? data.entries : [];
  const leaf = entries.find(entry => String(entry?.id || "") === String(data.leafId || ""));
  const editorText = leaf?.type === "custom" && leaf.customType === "__android_tree_edit__"
    ? String(leaf.data?.editorText ?? "")
    : null;
  const toolResultIds = new Set(
    entries
      .filter(entry => entry?.type === "message" && entry.message?.role === "toolResult")
      .map(entry => String(entry.message.toolCallId || ""))
      .filter(Boolean),
  );
  return {
    history: historyFromEntries(data),
    editorText,
    eventSeq: Number(response?.[responseEventSequence] ?? sequence),
    toolResultIds,
  };
}

function recoveryEventsAfterHistory(historySeq, latest, toolResultIds = new Set()) {
  const before = events.filter(item => item.seq <= historySeq);
  const carry = new Set();
  let agentStart = null;
  let openMessageStart = null;
  let compactionStart = null;
  let latestQueue = null;
  const activeTools = new Map();
  const completedToolCarry = new Set();

  for (const item of before) {
    const value = item.value || {};
    const type = value.type;
    if (type === "agent_start") {
      agentStart = item;
      openMessageStart = null;
      activeTools.clear();
      completedToolCarry.clear();
      latestQueue = null;
    } else if (type === "process_reset") {
      agentStart = null;
      openMessageStart = null;
      activeTools.clear();
      completedToolCarry.clear();
      latestQueue = null;
    } else if (type === "agent_settled" || type === "process_exit") {
      agentStart = null;
      openMessageStart = null;
      activeTools.clear();
      latestQueue = null;
    } else if (type === "message_start") {
      openMessageStart = item.seq;
    } else if (type === "message_end") {
      openMessageStart = null;
    } else if (type === "tool_execution_start") {
      activeTools.set(String(value.toolCallId || ""), { start: item, update: null });
    } else if (type === "tool_execution_update") {
      const key = String(value.toolCallId || "");
      const state = activeTools.get(key) || { start: null, update: null };
      state.update = item;
      activeTools.set(key, state);
    } else if (type === "tool_execution_end") {
      const key = String(value.toolCallId || "");
      const state = activeTools.get(key);
      if (key && !toolResultIds.has(key)) {
        if (state?.start) completedToolCarry.add(state.start.seq);
        if (state?.update) completedToolCarry.add(state.update.seq);
        completedToolCarry.add(item.seq);
      }
      activeTools.delete(key);
    } else if (type === "queue_update") {
      latestQueue = item;
    } else if (type === "compaction_start") {
      compactionStart = item;
    } else if (type === "compaction_end") {
      compactionStart = null;
    }
  }

  if (agentStart) carry.add(agentStart.seq);
  if (openMessageStart != null) {
    for (const item of before) {
      if (item.seq < openMessageStart) continue;
      if (item.value?.type === "message_start" || item.value?.type === "message_update") carry.add(item.seq);
    }
  }
  for (const state of activeTools.values()) {
    if (state.start) carry.add(state.start.seq);
    if (state.update) carry.add(state.update.seq);
  }
  for (const seq of completedToolCarry) carry.add(seq);
  if (latestQueue) carry.add(latestQueue.seq);
  if (compactionStart) carry.add(compactionStart.seq);

  return events.filter(item => item.seq <= latest && (carry.has(item.seq) || item.seq > historySeq));
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
      const info = await lstat(file);
      if (info.isFile()) files.push({ file, modified: info.mtimeMs });
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

function assertInside(root, target) {
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) throw new Error("path outside project");
}

async function safePath(relativePath = "") {
  const root = await realpath(cwd);
  const resolved = path.resolve(root, relativePath);
  assertInside(root, resolved);
  try {
    const canonical = await realpath(resolved);
    assertInside(root, canonical);
    return canonical;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    const parent = await realpath(path.dirname(resolved));
    assertInside(root, parent);
    return path.join(parent, path.basename(resolved));
  }
}

function safeAttachmentName(value) {
  const original = path.basename(String(value || "attachment"));
  return original.replace(/[^\p{L}\p{N}._ -]+/gu, "_").slice(0, 160) || "attachment";
}

async function streamAttachment(req, originalName) {
  const projectRoot = await realpath(cwd);
  const uploadCandidate = path.join(projectRoot, ".pi-android-uploads");
  await mkdir(uploadCandidate, { recursive: true, mode: 0o700 });
  const uploadRoot = await realpath(uploadCandidate);
  assertInside(projectRoot, uploadRoot);
  const storedName = `${Date.now()}-${randomUUID().slice(0, 8)}-${safeAttachmentName(originalName)}`;
  const target = path.join(uploadRoot, storedName);
  const temporary = `${target}.part`;
  let byteCount = 0;
  const limiter = new Transform({
    transform(chunk, _encoding, callback) {
      byteCount += chunk.length;
      callback(byteCount > maxUploadBytes ? new Error("upload too large") : null, chunk);
    },
  });
  try {
    await pipeline(req, limiter, createWriteStream(temporary, { flags: "wx", mode: 0o600 }));
    await rename(temporary, target);
    return { path: path.relative(cwd, target), byteCount };
  } finally {
    await unlink(temporary).catch(() => {});
  }
}

async function atomicWrite(target, content) {
  const temporary = path.join(path.dirname(target), `.pi-android-${process.pid}-${randomUUID()}.tmp`);
  let mode = 0o600;
  try { mode = (await stat(target)).mode & 0o777; } catch {}
  try {
    await writeFile(temporary, content, { encoding: "utf8", mode });
    await rename(temporary, target);
  } finally {
    await unlink(temporary).catch(() => {});
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    let settled = false;
    req.setEncoding("utf8");
    req.on("data", chunk => {
      if (settled) return;
      body += chunk;
      if (Buffer.byteLength(body) > maxRequestBytes) {
        settled = true;
        reject(new Error("request too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      if (!settled) {
        settled = true;
        resolve(body);
      }
    });
    req.on("error", error => {
      if (!settled) {
        settled = true;
        reject(error);
      }
    });
  });
}

function isAuthorized(req) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Bearer ")) return false;
  const supplied = Buffer.from(header.slice(7));
  const expected = Buffer.from(authToken);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

function send(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
  });
  res.end(body);
}

async function rpcResponse(res, command, timeoutMs) {
  try {
    const response = await rpc(command, timeoutMs);
    send(res, 200, response);
  } catch (error) {
    send(res, 500, { ok: false, error: String(error?.message || error) });
  }
}

function dispatchLongCommand(message) {
  if (!child || child.exitCode != null || !child.stdin.writable) throw new Error("Pi is not running");
  void rpc({ type: "prompt", message }, 24 * 60 * 60 * 1000).catch(error => {
    addEvent({ type: "extension_error", error: `Command failed: ${String(error?.message || error)}` });
  });
}

async function sessionStats() {
  const stats = await rpc({ type: "get_session_stats" });
  const messages = await rpc({ type: "get_messages" });
  const list = Array.isArray(messages?.data?.messages) ? messages.data.messages : [];
  const latest = [...list].reverse().find(message => message?.role === "assistant" && message?.usage);
  const usage = latest?.usage;
  const promptTokens = usage ? Number(usage.input || 0) + Number(usage.cacheRead || 0) + Number(usage.cacheWrite || 0) : 0;
  return {
    ...stats,
    data: {
      ...(stats.data || {}),
      latestCacheHitRate: promptTokens > 0 ? (Number(usage.cacheRead || 0) / promptTokens) * 100 : null,
    },
  };
}

let shuttingDown = false;

function shutdownBridge() {
  if (shuttingDown) return;
  shuttingDown = true;
  try { stopPi(); } catch {}
  const timer = setTimeout(() => process.exit(0), 500);
  timer.unref?.();
  try {
    server.close(() => process.exit(0));
  } catch {
    process.exit(0);
  }
}

process.on("SIGTERM", shutdownBridge);
process.on("SIGINT", shutdownBridge);

const server = http.createServer(async (req, res) => {
  try {
    if (!isAuthorized(req)) return send(res, 401, { ok: false, error: "unauthorized" });
    const url = new URL(req.url, `http://${req.headers.host || "127.0.0.1"}`);

    if (req.method === "GET" && url.pathname === "/health") {
      return send(res, 200, {
        ok: true,
        bridgeVersion,
        capabilities: bridgeCapabilities,
        piRunning: !!child && child.exitCode == null,
        cwd,
        launchCommand,
        activeSessionFile,
        latest: sequence,
        lastStderr,
        lastStdoutTail,
        lastExit,
        launcher: {
          node: nodeExecutable,
          pi: termuxPi,
          bash: termuxBash,
          prefix: termuxPrefix,
          home: termuxHome,
        },
      });
    }

    if (req.method === "GET" && url.pathname === "/reference") {
      const requested = String(url.searchParams.get("path") || "");
      if (!path.isAbsolute(requested)) throw new Error("file reference must be an absolute path");
      const target = await realpath(requested);
      const info = await stat(target);
      if (!info.isFile()) throw new Error("file reference is not a regular file");
      const handle = await open(target, "r");
      await handle.close();
      return send(res, 200, { ok: true, path: target, byteCount: info.size });
    }

    if (req.method === "POST" && url.pathname === "/upload") {
      const result = await streamAttachment(req, url.searchParams.get("name") || "attachment");
      return send(res, 200, { ok: true, ...result });
    }

    if (req.method === "POST" && url.pathname === "/shutdown") {
      send(res, 200, { ok: true });
      setImmediate(shutdownBridge);
      return;
    }

    if (req.method === "POST" && url.pathname === "/start") {
      const input = JSON.parse(await readBody(req) || "{}");
      await startPi(String(input.cwd || cwd), String(input.launchCommand || launchCommand));
      const state = await rpc({ type: "get_state" }, 60000);
      return send(res, 200, { ok: true, cwd, launchCommand, state: state.data || null });
    }

    if (req.method === "POST" && url.pathname === "/command") {
      const input = JSON.parse(await readBody(req) || "{}");
      const message = String(input.message || "").trim();
      if (!message.startsWith("/")) throw new Error("slash command required");
      dispatchLongCommand(message);
      return send(res, 202, { ok: true });
    }

    if (req.method === "POST" && url.pathname === "/prompt") {
      const input = JSON.parse(await readBody(req));
      const attachments = Array.isArray(input.attachments)
        ? input.attachments.filter(item => item && typeof item.path === "string" && item.path.trim())
        : [];
      let message = String(input.message || "");
      if (attachments.length) {
        const attachmentText = attachments.map(item => {
          const size = Number(item.byteCount);
          const sizeText = Number.isFinite(size) && size >= 0 ? ` (${size} bytes)` : "";
          return `- \`${item.path}\`${sizeText}`;
        }).join("\n");
        message = `${message || "请检查这些附件。"}\n\n文件引用（内容没有内嵌到消息中，请按需使用 read/bash 工具读取）：\n${attachmentText}`;
      }
      return rpcResponse(res, {
        type: "prompt",
        message,
        ...(input.streamingBehavior ? { streamingBehavior: input.streamingBehavior } : {}),
      });
    }

    if (req.method === "POST" && url.pathname === "/abort") return rpcResponse(res, { type: "abort" });
    if (req.method === "POST" && url.pathname === "/new-session") return rpcResponse(res, { type: "new_session" });
    if (req.method === "POST" && url.pathname === "/compact") {
      const input = JSON.parse(await readBody(req) || "{}");
      return rpcResponse(
        res,
        { type: "compact", ...(input.instructions ? { customInstructions: String(input.instructions) } : {}) },
        4 * 60 * 60 * 1000,
      );
    }
    if (req.method === "POST" && url.pathname === "/clone") return rpcResponse(res, { type: "clone" });

    if (req.method === "GET" && url.pathname === "/state") return rpcResponse(res, { type: "get_state" });
    if (req.method === "GET" && url.pathname === "/stats") return send(res, 200, await sessionStats());
    if (req.method === "GET" && url.pathname === "/models") return rpcResponse(res, { type: "get_available_models" });
    if (req.method === "GET" && url.pathname === "/commands") return rpcResponse(res, { type: "get_commands" });
    if (req.method === "GET" && url.pathname === "/messages") return rpcResponse(res, { type: "get_messages" });
    if (req.method === "GET" && url.pathname === "/history") {
      const durable = await durableHistory();
      return send(res, 200, { ok: true, history: durable.history });
    }
    if (req.method === "GET" && url.pathname === "/snapshot") {
      const durable = await durableHistory();
      const latest = sequence;
      const activeEvents = recoveryEventsAfterHistory(durable.eventSeq, latest, durable.toolResultIds);
      const recoveryEvents = [...persistentUiEvents.values(), ...(latestQueueEvent ? [latestQueueEvent] : []), ...activeEvents]
        .filter((item, index, all) => all.findIndex((candidate) => candidate.seq === item.seq) === index)
        .sort((left, right) => left.seq - right.seq);
      return send(res, 200, {
        ok: true,
        history: durable.history,
        editorText: durable.editorText,
        events: recoveryEvents,
        latest,
        pendingUi: [...pendingUiRequests.values()],
      });
    }

    if (req.method === "GET" && url.pathname === "/sessions") {
      const sessions = await listSessions();
      return send(res, 200, { sessions });
    }

    if (req.method === "POST" && url.pathname === "/switch-session") {
      const input = JSON.parse(await readBody(req) || "{}");
      const target = path.resolve(String(input.path || ""));
      const dir = path.resolve(sessionDirForCwd(cwd));
      const sessionRoot = await realpath(dir);
      const canonicalTarget = await realpath(target);
      if (!canonicalTarget.endsWith(".jsonl") ||
          (canonicalTarget !== sessionRoot && !canonicalTarget.startsWith(`${sessionRoot}${path.sep}`))) {
        throw new Error("session path outside current project");
      }
      if (!(await stat(canonicalTarget)).isFile()) throw new Error("session file not found");
      const result = await rpc({ type: "switch_session", sessionPath: canonicalTarget }, 30000);
      const state = await rpc({ type: "get_state" }, 60000);
      activeSessionFile = String(state?.data?.sessionFile || canonicalTarget);
      return send(res, 200, { ok: true, result: result.data || null, state: state.data || null });
    }

    if (req.method === "POST" && url.pathname === "/model") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "set_model", provider: String(input.provider || ""), modelId: String(input.modelId || "") });
    }
    if (req.method === "POST" && url.pathname === "/cycle-model") return rpcResponse(res, { type: "cycle_model" });

    if (req.method === "POST" && url.pathname === "/thinking") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "set_thinking_level", level: String(input.level || "off") });
    }
    if (req.method === "POST" && url.pathname === "/cycle-thinking") return rpcResponse(res, { type: "cycle_thinking_level" });

    if (req.method === "POST" && url.pathname === "/auto-compaction") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "set_auto_compaction", enabled: Boolean(input.enabled) });
    }

    if (req.method === "GET" && url.pathname === "/last-assistant") {
      return rpcResponse(res, { type: "get_last_assistant_text" });
    }

    if (req.method === "POST" && url.pathname === "/export-html") {
      const input = JSON.parse(await readBody(req) || "{}");
      return rpcResponse(res, {
        type: "export_html",
        ...(input.outputPath ? { outputPath: String(input.outputPath) } : {}),
      }, 120_000);
    }

    if (req.method === "POST" && url.pathname === "/bash") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, {
        type: "bash",
        command: String(input.command || ""),
        excludeFromContext: Boolean(input.excludeFromContext),
      }, 4 * 60 * 60 * 1000);
    }

    if (req.method === "POST" && url.pathname === "/abort-bash") return rpcResponse(res, { type: "abort_bash" });

    if (req.method === "POST" && url.pathname === "/extension-ui") {
      const input = JSON.parse(await readBody(req));
      pendingUiRequests.delete(String(input.id || ""));
      sendRaw({ type: "extension_ui_response", ...input });
      return send(res, 200, { ok: true });
    }

    if (req.method === "GET" && url.pathname === "/events") {
      const after = Number(url.searchParams.get("after") || 0);
      const waitMs = Math.min(30_000, Math.max(0, Number(url.searchParams.get("wait") || 0)));
      await waitForEvent(after, waitMs);
      const earliest = events[0]?.seq ?? sequence;
      return send(res, 200, {
        events: events.filter(item => item.seq > after),
        latest: sequence,
        earliest,
        gap: after > sequence || (after > 0 && after < earliest - 1),
      });
    }

    if (req.method === "GET" && url.pathname === "/files") {
      const relativePath = url.searchParams.get("path") || "";
      const entries = await readdir(await safePath(relativePath), { withFileTypes: true });
      entries.sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
      return send(res, 200, {
        path: relativePath,
        entries: entries
          .filter(item => !item.name.startsWith("."))
          .map(item => ({ name: item.name, type: item.isDirectory() ? "directory" : "file", path: path.posix.join(relativePath, item.name) })),
      });
    }

    if (req.method === "GET" && url.pathname === "/file") {
      const relativePath = url.searchParams.get("path") || "";
      const target = await safePath(relativePath);
      const info = await stat(target);
      if (!info.isFile()) throw new Error("file path is not a regular file");
      const maxBytes = 2_000_000;
      const handle = await open(target, "r");
      let content;
      try {
        const buffer = Buffer.alloc(Math.min(info.size, maxBytes));
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
        const decoder = new StringDecoder("utf8");
        content = decoder.write(buffer.subarray(0, bytesRead));
        if (info.size <= maxBytes) content += decoder.end();
      } finally {
        await handle.close();
      }
      return send(res, 200, { path: relativePath, content, truncated: info.size > maxBytes });
    }

    if (req.method === "POST" && url.pathname === "/file") {
      const input = JSON.parse(await readBody(req));
      const relativePath = String(input.path || "");
      const content = String(input.content ?? "");
      if (!relativePath || relativePath.endsWith("/")) throw new Error("file path required");
      if (content.length > 4_000_000) throw new Error("file too large");
      await atomicWrite(await safePath(relativePath), content);
      return send(res, 200, { ok: true, path: relativePath, bytes: Buffer.byteLength(content) });
    }

    if (req.method === "GET" && url.pathname === "/diff") {
      try {
        const result = await execFileAsync("git", ["-C", cwd, "diff", "HEAD", "--"], { maxBuffer: 4_000_000 });
        return send(res, 200, { diff: result.stdout });
      } catch (error) {
        return send(res, 200, { diff: error.stdout || "", error: error.stderr || "" });
      }
    }

    send(res, 404, { ok: false, error: "not found" });
  } catch (error) {
    send(res, 500, { ok: false, error: String(error?.message || error) });
  }
});

function removeOwnPidFile() {
  try {
    if (readFileSync(pidFile, "utf8").trim() === String(process.pid)) unlinkSync(pidFile);
  } catch {}
}

process.on("exit", removeOwnPidFile);
server.requestTimeout = 0;
server.timeout = 0;
server.listen(port, "127.0.0.1", () => {
  writeFileSync(pidFile, String(process.pid), { encoding: "utf8", mode: 0o600 });
  console.log(`Pi Android bridge ${bridgeVersion} listening on 127.0.0.1:${port}`);
});
