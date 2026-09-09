import http from "node:http";
import { spawn, execFile } from "node:child_process";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { StringDecoder } from "node:string_decoder";
import { existsSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { open, readdir, readFile, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const port = Number(process.env.PI_ANDROID_PORT || 17649);
const bridgeVersion = "2026-09-09.12";
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
let child = null;
let cwd = termuxHome;
let launchCommand = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts";
let sequence = 0;
let lastStderr = "";
let lastStdoutTail = "";
let lastExit = null;
const events = [];
const maxEvents = 1000;
const pending = new Map();

function addEvent(value) {
  const event = { seq: ++sequence, receivedAt: Date.now(), value };
  events.push(event);
  if (events.length > maxEvents) events.shift();
}

function settlePending(id, value) {
  const item = pending.get(id);
  if (!item) return false;
  pending.delete(id);
  clearTimeout(item.timer);
  if (value.success === false) item.reject(new Error(value.error || `${value.command || "RPC"} failed`));
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
      try { addEvent(JSON.parse(rest)); }
      catch { addEvent({ type: "raw", line: rest }); }
    }
  });
}

function stopPi() {
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

  try {
    child = spawnPi(launchCommand, cwd);
  } catch (error) {
    throw new Error(`Pi launcher failed: ${String(error?.message || error)}`);
  }

  attachJsonl(child.stdout);
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", text => {
    lastStderr = (lastStderr + text).slice(-8000);
    addEvent({ type: "stderr", text });
  });
  child.on("error", error => {
    lastStderr = (lastStderr + `\n${error.message}`).slice(-8000);
    addEvent({ type: "stderr", text: error.message });
  });
  child.on("exit", (code, signal) => {
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
  if (!child || child.exitCode != null) {
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
    pending.set(id, { resolve, reject, timer });
    sendRaw({ ...command, id });
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
      if (Buffer.byteLength(body) > 4_000_000) {
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
        piRunning: !!child && child.exitCode == null,
        cwd,
        launchCommand,
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

    if (req.method === "POST" && url.pathname === "/shutdown") {
      send(res, 200, { ok: true });
      setImmediate(shutdownBridge);
      return;
    }

    if (req.method === "POST" && url.pathname === "/start") {
      const input = JSON.parse(await readBody(req) || "{}");
      await startPi(String(input.cwd || cwd), String(input.launchCommand || launchCommand));
      const state = await rpc({ type: "get_state" }, 10000);
      return send(res, 200, { ok: true, cwd, launchCommand, state: state.data || null });
    }

    if (req.method === "POST" && url.pathname === "/prompt") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, {
        type: "prompt",
        message: String(input.message || ""),
        ...(input.streamingBehavior ? { streamingBehavior: input.streamingBehavior } : {}),
      });
    }

    if (req.method === "POST" && url.pathname === "/abort") return rpcResponse(res, { type: "abort" });
    if (req.method === "POST" && url.pathname === "/new-session") return rpcResponse(res, { type: "new_session" });
    if (req.method === "POST" && url.pathname === "/compact") {
      const input = JSON.parse(await readBody(req) || "{}");
      return rpcResponse(res, { type: "compact", ...(input.instructions ? { customInstructions: String(input.instructions) } : {}) }, 120000);
    }
    if (req.method === "POST" && url.pathname === "/clone") return rpcResponse(res, { type: "clone" });

    if (req.method === "GET" && url.pathname === "/state") return rpcResponse(res, { type: "get_state" });
    if (req.method === "GET" && url.pathname === "/stats") return send(res, 200, await sessionStats());
    if (req.method === "GET" && url.pathname === "/models") return rpcResponse(res, { type: "get_available_models" });
    if (req.method === "GET" && url.pathname === "/commands") return rpcResponse(res, { type: "get_commands" });
    if (req.method === "GET" && url.pathname === "/messages") return rpcResponse(res, { type: "get_messages" });

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

    if (req.method === "POST" && url.pathname === "/model") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "set_model", provider: String(input.provider || ""), modelId: String(input.modelId || "") });
    }

    if (req.method === "POST" && url.pathname === "/thinking") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "set_thinking_level", level: String(input.level || "off") });
    }

    if (req.method === "POST" && url.pathname === "/bash") {
      const input = JSON.parse(await readBody(req));
      return rpcResponse(res, { type: "bash", command: String(input.command || "") }, 10 * 60 * 1000);
    }

    if (req.method === "POST" && url.pathname === "/abort-bash") return rpcResponse(res, { type: "abort_bash" });

    if (req.method === "POST" && url.pathname === "/extension-ui") {
      const input = JSON.parse(await readBody(req));
      sendRaw({ type: "extension_ui_response", ...input });
      return send(res, 200, { ok: true });
    }

    if (req.method === "GET" && url.pathname === "/events") {
      const after = Number(url.searchParams.get("after") || 0);
      return send(res, 200, { events: events.filter(item => item.seq > after), latest: sequence });
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
      const content = await readFile(await safePath(relativePath), "utf8");
      return send(res, 200, { path: relativePath, content: content.slice(0, 2_000_000), truncated: content.length > 2_000_000 });
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
server.listen(port, "127.0.0.1", () => {
  writeFileSync(pidFile, String(process.pid), { encoding: "utf8", mode: 0o600 });
  console.log(`Pi Android bridge ${bridgeVersion} listening on 127.0.0.1:${port}`);
});
