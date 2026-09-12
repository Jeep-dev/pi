import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-multi-"));
const prefix = path.join(home, "prefix");
const bin = path.join(prefix, "bin");
const cwd = path.join(home, "same-project");
await mkdir(bin, { recursive: true });
await mkdir(cwd, { recursive: true });

const fakePi = path.join(bin, "pi");
await writeFile(fakePi, `
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const args = process.argv.slice(2);
function valueOf(option) {
  const index = args.indexOf(option);
  return index >= 0 ? args[index + 1] || "" : "";
}
const sessionId = valueOf("--session-id") || "missing-session-id";
const sessionDir = valueOf("--session-dir") || join(process.env.HOME, ".pi", "agent", "sessions");
const sessionFile = join(sessionDir, sessionId + ".jsonl");
mkdirSync(dirname(sessionFile), { recursive: true });
if (!existsSync(sessionFile)) {
  writeFileSync(sessionFile, JSON.stringify({ type: "session", version: 3, id: sessionId, cwd: process.cwd() }) + "\\n");
}
let entries = readFileSync(sessionFile, "utf8").split(/\\r?\\n/).filter(Boolean).map(line => JSON.parse(line));
let leafId = entries.filter(entry => entry.type !== "session").at(-1)?.id || null;
let nextEntry = 0;
function persist(entry) {
  entries.push(entry);
  leafId = entry.id;
  writeFileSync(sessionFile, entries.map(item => JSON.stringify(item)).join("\\n") + "\\n");
}
function response(command, data = {}) {
  process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: true, data }) + "\\n");
}
function textContent(message) {
  return typeof message?.content === "string"
    ? message.content
    : (message?.content || []).filter(part => part?.type === "text").map(part => part.text || "").join("\\n");
}
process.on("SIGTERM", () => process.exit(0));
let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const command = JSON.parse(buffer.slice(0, newline));
    buffer = buffer.slice(newline + 1);
    if (command.type === "get_state") {
      response(command, {
        sessionId,
        sessionFile,
        isStreaming: false,
        isCompacting: false,
        messageCount: entries.filter(entry => entry.type === "message").length,
        noTools: args.includes("--no-tools"),
      });
    } else if (command.type === "get_entries") {
      response(command, { leafId, entries: entries.filter(entry => entry.type !== "session") });
    } else if (command.type === "prompt") {
      const id = sessionId + "-" + (++nextEntry);
      persist({
        type: "message",
        id,
        parentId: leafId,
        timestamp: new Date().toISOString(),
        message: { role: "user", content: [{ type: "text", text: String(command.message || "") }] },
      });
      response(command);
      process.stdout.write(JSON.stringify({ type: "agent_start" }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text: sessionId }] } }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "agent_settled" }) + "\\n");
    } else {
      response(command);
    }
  }
});
`);
await chmod(fakePi, 0o700);

const basePort = 30_000 + (process.pid % 800) * 3;
const records = ["A", "B", "C"].map((id, index) => ({
  id,
  startupArguments: index === 1 ? "--no-tools" : "",
  token: randomBytes(32).toString("base64url"),
  port: basePort + index,
  sessionDir: path.join(home, "android-sessions", id),
  pidFile: path.join(home, ".pi", "android", id, "bridge.pid"),
}));
for (const record of records) await mkdir(path.dirname(record.pidFile), { recursive: true });

function startBridge(record) {
  const child = spawn(process.execPath, [bridgePath], {
    env: {
      ...process.env,
      HOME: home,
      PREFIX: prefix,
      PI_ANDROID_PORT: String(record.port),
      PI_ANDROID_TOKEN: record.token,
      PI_ANDROID_PID_FILE: record.pidFile,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostics = "";
  child.stdout.on("data", chunk => { diagnostics += chunk; });
  child.stderr.on("data", chunk => { diagnostics += chunk; });
  return { child, diagnostics: () => diagnostics };
}

function headers(record) {
  return { Authorization: `Bearer ${record.token}`, "Content-Type": "application/json" };
}
async function request(record, pathname, init = {}) {
  return fetch(`http://127.0.0.1:${record.port}${pathname}`, {
    ...init,
    headers: { ...headers(record), ...(init.headers || {}) },
  });
}
async function waitForHealth(record) {
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      const response = await request(record, "/health");
      if (response.status === 200) return response.json();
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw new Error(`bridge did not start on ${record.port}`);
}
async function startPi(record) {
  const launchCommand = `pi --mode rpc --session-dir ${record.sessionDir} --session-id ${record.id} ${record.startupArguments}`;
  const response = await request(record, "/start", {
    method: "POST",
    body: JSON.stringify({ cwd, launchCommand }),
  });
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return { launchCommand, state: JSON.parse(text).state };
}
async function stopBridge(record) {
  await request(record, "/shutdown", { method: "POST", body: "{}" }).catch(() => {});
  await new Promise(resolve => setTimeout(resolve, 120));
}

let bridges = records.map(startBridge);
try {
  await Promise.all(records.map(waitForHealth));
  await Promise.all(records.map(startPi));
  const initialStates = await Promise.all(records.map(record => request(record, "/state").then(response => response.json())));
  const initialHealthAfterStart = await Promise.all(records.map(record => request(record, "/health").then(response => response.json())));

  assert.equal(new Set(initialHealthAfterStart.map(health => health.bridgePid)).size, 3, "A/B/C need distinct Bridge processes");
  assert.equal(new Set(initialHealthAfterStart.map(health => health.piPid)).size, 3, "A/B/C need distinct Pi processes");
  assert.deepEqual(initialHealthAfterStart.map(health => health.port), records.map(record => record.port));
  assert.deepEqual(initialHealthAfterStart.map(health => health.cwd), [cwd, cwd, cwd]);
  assert.deepEqual(initialStates.map(result => result.data.sessionId), ["A", "B", "C"]);
  assert.deepEqual(initialStates.map(result => result.data.noTools), [false, true, false]);
  assert.equal(new Set(initialStates.map(result => result.data.sessionFile)).size, 3, "session files must be distinct even with equal cwd");
  assert.deepEqual(initialStates.map(result => result.data.sessionFile), records.map(record => path.join(record.sessionDir, `${record.id}.jsonl`)));

  for (const [record, marker] of records.map((record, index) => [record, ["AAA", "BBB", "CCC"][index]])) {
    const prompt = await request(record, "/prompt", {
      method: "POST",
      body: JSON.stringify({ message: marker }),
    });
    assert.equal(prompt.status, 200);
  }
  await new Promise(resolve => setTimeout(resolve, 40));
  const histories = await Promise.all(records.map(record => request(record, "/history").then(response => response.json())));
  assert.deepEqual(
    histories.map(result => result.history.filter(item => item.role === "user").map(item => item.text)),
    [["AAA"], ["BBB"], ["CCC"]],
    "same cwd must not share conversation history",
  );

  const filesBeforeRestart = initialStates.map(result => result.data.sessionFile);
  const pidsBeforeRestart = initialHealthAfterStart.map(health => [health.bridgePid, health.piPid]);
  await Promise.all(records.map(stopBridge));
  bridges.forEach(({ child }) => child.kill("SIGTERM"));
  bridges = records.map(startBridge);
  await Promise.all(records.map(waitForHealth));
  await Promise.all(records.map(startPi));
  const statesAfterRestart = await Promise.all(records.map(record => request(record, "/state").then(response => response.json())));
  const healthAfterRestart = await Promise.all(records.map(record => request(record, "/health").then(response => response.json())));
  assert.deepEqual(statesAfterRestart.map(result => result.data.sessionId), ["A", "B", "C"]);
  assert.deepEqual(statesAfterRestart.map(result => result.data.noTools), [false, true, false]);
  assert.deepEqual(statesAfterRestart.map(result => result.data.sessionFile), filesBeforeRestart, "restart must reopen each own file");
  assert.deepEqual(
    (await Promise.all(records.map(record => request(record, "/history").then(response => response.json())))).map(result => result.history.filter(item => item.role === "user").map(item => item.text)),
    [["AAA"], ["BBB"], ["CCC"]],
    "conversation isolation must survive App/Bridge restart",
  );
  assert.ok(healthAfterRestart.every((health, index) => health.bridgePid !== pidsBeforeRestart[index][0] && health.piPid !== pidsBeforeRestart[index][1]), "restart must create new process handles");

  await stopBridge(records[1]);
  const aAfterB = await request(records[0], "/health").then(response => response.json());
  const cAfterB = await request(records[2], "/health").then(response => response.json());
  assert.equal(aAfterB.piRunning, true, "stopping B must not stop A");
  assert.equal(cAfterB.piRunning, true, "stopping B must not stop C");
  console.log("Multi-session conversation isolation tests passed");
} finally {
  await Promise.all(records.map(stopBridge));
  bridges.forEach(({ child }) => child.kill("SIGTERM"));
  await rm(home, { recursive: true, force: true });
}
