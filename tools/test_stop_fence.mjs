import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-stop-"));
const prefix = path.join(home, "prefix");
const bin = path.join(prefix, "bin");
const port = 31_000 + (process.pid % 1_000);
const token = randomBytes(32).toString("base64url");
const cwd = path.join(home, "project");
await mkdir(bin, { recursive: true });
await mkdir(cwd, { recursive: true });
await mkdir(path.join(home, ".pi", "android"), { recursive: true });
const fakePi = path.join(bin, "pi");
await writeFile(fakePi, `
let buffer = "";
let running = false;
let toolTimer = null;
const order = [];
const events = [];
const sessionFile = process.cwd() + "/fake-session.jsonl";
function emit(value) {
  events.push(value);
  process.stdout.write(JSON.stringify(value) + "\\n");
}
function response(command, data = {}) {
  process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: true, data }) + "\\n");
}
process.on("SIGTERM", () => process.exit(0));
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const command = JSON.parse(buffer.slice(0, newline));
    buffer = buffer.slice(newline + 1);
    if (command.type === "get_state") {
      response(command, { sessionId: "stop-test", sessionFile, isStreaming: running, isCompacting: false, messageCount: 0 });
    } else if (command.type === "prompt" && command.message === "long") {
      running = true;
      emit({ type: "agent_start" });
      toolTimer = setTimeout(() => {
        if (!running) return;
        emit({ type: "tool_execution_start", toolCallId: "late-tool", toolName: "phone_action", args: { action: "must-not-run-after-stop" } });
      }, 200);
      response(command);
    } else if (command.type === "clear_queue") {
      order.push("clear_queue");
      if (toolTimer) clearTimeout(toolTimer);
      response(command, { steering: ["queued-but-cleared"], followUp: ["follow-up-cleared"] });
    } else if (command.type === "abort_bash") {
      order.push("abort_bash");
      response(command);
    } else if (command.type === "abort") {
      order.push("abort");
      setTimeout(() => {
        running = false;
        if (toolTimer) clearTimeout(toolTimer);
        emit({ type: "message_end", message: { role: "assistant", content: [], stopReason: "aborted" } });
        emit({ type: "agent_settled" });
        process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: order.includes("clear_queue"), data: {} }) + "\\n");
      }, 350);
    } else if (command.type === "prompt") {
      response(command);
    } else if (command.type === "get_entries") {
      response(command, { leafId: null, entries: [] });
    } else {
      response(command);
    }
  }
});
`);
await chmod(fakePi, 0o700);

const child = spawn(process.execPath, [bridgePath], {
  env: {
    ...process.env,
    HOME: home,
    PREFIX: prefix,
    PI_ANDROID_PORT: String(port),
    PI_ANDROID_TOKEN: token,
    PI_ANDROID_PID_FILE: path.join(home, ".pi", "android", "bridge.pid"),
  },
  stdio: ["ignore", "pipe", "pipe"],
});
let diagnostics = "";
child.stdout.on("data", chunk => { diagnostics += chunk; });
child.stderr.on("data", chunk => { diagnostics += chunk; });
const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function jsonRequest(pathname, init = {}) {
  return fetch(`http://127.0.0.1:${port}${pathname}`, { ...init, headers: { ...headers, ...(init.headers || {}) } });
}

try {
  let health;
  for (let attempt = 0; attempt < 80; attempt++) {
    try {
      health = await jsonRequest("/health");
      if (health.status === 200) break;
    } catch {}
    await wait(25);
  }
  assert.equal(health?.status, 200, diagnostics);

  const started = await jsonRequest("/start", {
    method: "POST",
    body: JSON.stringify({ cwd, launchCommand: "pi --mode rpc" }),
  });
  const startedText = await started.text();
  assert.equal(started.status, 200, startedText);

  const longPrompt = await jsonRequest("/prompt", {
    method: "POST",
    body: JSON.stringify({ message: "long" }),
  });
  assert.equal(longPrompt.status, 200);
  await wait(20);

  const stopPromise = jsonRequest("/stop", { method: "POST", body: "{}" });
  await wait(20);
  const rejectedDuringStop = await jsonRequest("/prompt", {
    method: "POST",
    body: JSON.stringify({ message: "must-be-rejected" }),
  });
  assert.equal(rejectedDuringStop.status, 500, "new work must be fenced while Stop is in progress");
  assert.match((await rejectedDuringStop.json()).error, /Stop in progress/);

  const stop = await stopPromise;
  const stopText = await stop.text();
  assert.equal(stop.status, 200, stopText);
  const stopData = JSON.parse(stopText);
  assert.equal(stopData.cleared, true);
  assert.equal(stopData.aborted, true);

  const afterStop = await jsonRequest("/prompt", {
    method: "POST",
    body: JSON.stringify({ message: "allowed-after-stop" }),
  });
  assert.equal(afterStop.status, 200);
  await wait(180);

  const events = await jsonRequest("/events?after=0&wait=0").then(response => response.json());
  assert.equal(
    events.events.filter(item => item.value?.type === "tool_execution_start").length,
    0,
    "a tool action queued behind the active run must not execute after Stop",
  );
  // The observable hard-stop fence, queue-clear result, and absence of the
  // delayed tool action cover the bridge's cancellation contract.
  assert.equal(stopData.bashAborted, true);
  console.log("Stop fence tests passed");
} finally {
  child.kill("SIGTERM");
  await wait(300);
  if (child.exitCode == null) child.kill("SIGKILL");
  await rm(home, { recursive: true, force: true });
}
