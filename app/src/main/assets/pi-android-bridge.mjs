import http from "node:http";
import { spawn, execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { StringDecoder } from "node:string_decoder";
import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const port = Number(process.env.PI_ANDROID_PORT || 17642);
const execFileAsync = promisify(execFile);
let child = null;
let cwd = process.env.HOME || process.cwd();
let launchCommand = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts";
let sequence = 0;
let lastStderr = "";
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

async function startPi(nextCwd, nextLaunchCommand) {
  stopPi();
  cwd = nextCwd || cwd;
  launchCommand = (nextLaunchCommand || launchCommand).trim();
  if (!launchCommand) throw new Error("Pi launch command is empty");
  lastStderr = "";
  child = spawn("bash", ["-lc", launchCommand], {
    cwd,
    env: process.env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  attachJsonl(child.stdout);
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", text => {
    lastStderr = (lastStderr + text).slice(-8000);
    addEvent({ type: "stderr", text });
  });
  child.on("exit", (code, signal) => {
    addEvent({ type: "process_exit", code, signal, stderr: lastStderr });
    for (const [, item] of pending) {
      clearTimeout(item.timer);
      item.reject(new Error(`Pi exited (${code ?? signal ?? "unknown"})${lastStderr ? `: ${lastStderr.trim()}` : ""}`));
    }
    pending.clear();
    child = null;
  });
  await new Promise(resolve => setTimeout(resolve, 350));
  if (!child || child.exitCode != null) throw new Error(lastStderr.trim() || "Pi failed to start");
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

function safePath(relativePath = "") {
  const resolved = path.resolve(cwd, relativePath);
  if (resolved !== cwd && !resolved.startsWith(`${cwd}${path.sep}`)) throw new Error("path outside project");
  return resolved;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 4_000_000) reject(new Error("request too large"));
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
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

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && url.pathname === "/health") {
      return send(res, 200, {
        ok: true,
        piRunning: !!child && child.exitCode == null,
        cwd,
        launchCommand,
        latest: sequence,
        lastStderr,
      });
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
    if (req.method === "GET" && url.pathname === "/stats") return rpcResponse(res, { type: "get_session_stats" });
    if (req.method === "GET" && url.pathname === "/models") return rpcResponse(res, { type: "get_available_models" });
    if (req.method === "GET" && url.pathname === "/commands") return rpcResponse(res, { type: "get_commands" });
    if (req.method === "GET" && url.pathname === "/messages") return rpcResponse(res, { type: "get_messages" });

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
      const entries = await readdir(safePath(relativePath), { withFileTypes: true });
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
      const content = await readFile(safePath(relativePath), "utf8");
      return send(res, 200, { path: relativePath, content: content.slice(0, 2_000_000), truncated: content.length > 2_000_000 });
    }

    if (req.method === "POST" && url.pathname === "/file") {
      const input = JSON.parse(await readBody(req));
      const relativePath = String(input.path || "");
      const content = String(input.content ?? "");
      if (!relativePath || relativePath.endsWith("/")) throw new Error("file path required");
      if (content.length > 4_000_000) throw new Error("file too large");
      await writeFile(safePath(relativePath), content, "utf8");
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

function shutdown() {
  stopPi();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 800).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

server.listen(port, "127.0.0.1", () => {
  console.log(`Pi Android bridge listening on 127.0.0.1:${port}`);
});
