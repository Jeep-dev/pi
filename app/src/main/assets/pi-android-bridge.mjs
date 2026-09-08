import http from "node:http";
import { spawn, execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const port = Number(process.env.PI_ANDROID_PORT || 17642);
let child = null;
let cwd = process.env.PI_ANDROID_CWD || process.cwd();
let sequence = 0;
const events = [];
const maxEvents = 500;
const execFileAsync = promisify(execFile);

function safePath(relativePath = "") {
  const resolved = path.resolve(cwd, relativePath);
  if (resolved !== cwd && !resolved.startsWith(`${cwd}${path.sep}`)) throw new Error("path outside project");
  return resolved;
}

function addEvent(value) {
  const event = { seq: ++sequence, receivedAt: Date.now(), value };
  events.push(event);
  if (events.length > maxEvents) events.shift();
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => { body += chunk; if (body.length > 2_000_000) reject(new Error("request too large")); });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function send(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(body), "access-control-allow-origin": "*" });
  res.end(body);
}

function startPi(nextCwd) {
  if (child && !child.killed && cwd === nextCwd) return;
  if (child && !child.killed) child.kill("SIGTERM");
  cwd = nextCwd || cwd;
  child = spawn("pi", ["--mode", "rpc", "--session-dir", `${process.env.HOME}/.pi/agent/sessions`], { cwd, env: process.env });
  child.stdout.setEncoding("utf8");
  let pending = "";
  child.stdout.on("data", chunk => {
    pending += chunk;
    const lines = pending.split("\n");
    pending = lines.pop() || "";
    for (const line of lines) { if (!line.trim()) continue; try { addEvent(JSON.parse(line)); } catch { addEvent({ type: "raw", line }); } }
  });
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", chunk => addEvent({ type: "stderr", text: chunk }));
  child.on("exit", (code, signal) => { addEvent({ type: "process_exit", code, signal }); child = null; });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { ok: true, piRunning: !!child, cwd, latest: sequence });
    if (req.method === "GET" && url.pathname === "/files") {
      const relativePath = url.searchParams.get("path") || "";
      const entries = await readdir(safePath(relativePath), { withFileTypes: true });
      entries.sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
      return send(res, 200, { path: relativePath, entries: entries.filter(item => !item.name.startsWith(".")).map(item => ({ name: item.name, type: item.isDirectory() ? "directory" : "file", path: path.posix.join(relativePath, item.name) })) });
    }
    if (req.method === "GET" && url.pathname === "/file") {
      const relativePath = url.searchParams.get("path") || "";
      const content = await readFile(safePath(relativePath), "utf8");
      return send(res, 200, { path: relativePath, content: content.slice(0, 2_000_000), truncated: content.length > 2_000_000 });
    }
    if (req.method === "POST" && url.pathname === "/file") {
      const input = JSON.parse(await readBody(req));
      const relativePath = String(input.path || "");
      if (!relativePath || relativePath.endsWith("/")) throw new Error("file path required");
      const content = String(input.content ?? "");
      if (content.length > 4_000_000) throw new Error("file too large");
      await writeFile(safePath(relativePath), content, "utf8");
      return send(res, 200, { ok: true, path: relativePath, bytes: Buffer.byteLength(content) });
    }
    if (req.method === "GET" && url.pathname === "/diff") {
      try {
        const result = await execFileAsync("git", ["-C", cwd, "diff", "HEAD", "--"], { maxBuffer: 4_000_000 });
        return send(res, 200, { diff: result.stdout });
      } catch (error) {
        return send(res, 200, { diff: error.stdout || "" });
      }
    }
    if (req.method === "POST" && url.pathname === "/start") {
      const input = JSON.parse(await readBody(req) || "{}");
      startPi(input.cwd || cwd);
      return send(res, 200, { ok: true, cwd });
    }
    if (req.method === "GET" && url.pathname === "/events") {
      const after = Number(url.searchParams.get("after") || 0);
      return send(res, 200, { events: events.filter(item => item.seq > after), latest: sequence });
    }
    if (req.method === "POST" && url.pathname === "/prompt") {
      if (!child) return send(res, 409, { ok: false, error: "Pi is not running" });
      const input = JSON.parse(await readBody(req));
      const id = input.id || randomUUID();
      child.stdin.write(JSON.stringify({ id, type: "prompt", message: String(input.message || "") }) + "\n");
      return send(res, 200, { ok: true, id });
    }
    if (req.method === "POST" && url.pathname === "/terminal") {
      const input = JSON.parse(await readBody(req));
      const command = String(input.command || "").trim();
      if (!command) return send(res, 400, { ok: false, error: "command required" });
      const id = input.id || randomUUID();
      const terminalProcess = spawn("bash", ["-lc", command], { cwd, env: process.env });
      addEvent({ type: "terminal_start", id, command });
      terminalProcess.stdout.setEncoding("utf8");
      terminalProcess.stderr.setEncoding("utf8");
      terminalProcess.stdout.on("data", text => addEvent({ type: "terminal_output", id, stream: "stdout", text }));
      terminalProcess.stderr.on("data", text => addEvent({ type: "terminal_output", id, stream: "stderr", text }));
      terminalProcess.on("exit", (code, signal) => addEvent({ type: "terminal_end", id, code, signal }));
      return send(res, 200, { ok: true, id });
    }
    send(res, 404, { ok: false, error: "not found" });
  } catch (error) { send(res, 500, { ok: false, error: String(error?.message || error) }); }
});
server.listen(port, "127.0.0.1", () => console.log(`Pi Android bridge listening on 127.0.0.1:${port}`));
