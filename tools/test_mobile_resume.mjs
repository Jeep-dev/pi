import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-resume-"));
const prefix = path.join(home, "prefix");
const bin = path.join(prefix, "bin");
const cwd = path.join(home, "same-project");
const legacySafeCwd = `--${cwd.replace(/^[/\\\\]/, "").replace(/[/\\\\:]/g, "-")}--`;
const legacyDir = path.join(home, ".pi", "agent", "sessions", legacySafeCwd);
const legacyPath = path.join(legacyDir, "legacy.jsonl");
await mkdir(bin, { recursive: true });
await mkdir(cwd, { recursive: true });
await mkdir(legacyDir, { recursive: true });
const legacyTimestamp = new Date().toISOString();
await writeFile(legacyPath, [
  { type: "session", version: 3, id: "LEGACY", timestamp: legacyTimestamp, cwd },
  {
    type: "message",
    id: "legacy-user",
    parentId: null,
    timestamp: legacyTimestamp,
    message: { role: "user", content: "LEGACY" },
  },
  {
    type: "message",
    id: "legacy-assistant",
    parentId: "legacy-user",
    timestamp: legacyTimestamp,
    message: { role: "assistant", content: [{ type: "text", text: "reply-LEGACY" }] },
  },
].map(entry => JSON.stringify(entry)).join("\n") + "\n");

const fakePi = path.join(bin, "pi");
await writeFile(fakePi, `
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const args = process.argv.slice(2);
function valueOf(option) {
  const index = args.indexOf(option);
  return index >= 0 ? args[index + 1] || "" : "";
}
function load(file) {
  return readFileSync(file, "utf8").split(/\\r?\\n/).filter(Boolean).map(line => JSON.parse(line));
}
function save(file, entries) {
  writeFileSync(file, entries.map(entry => JSON.stringify(entry)).join("\\n") + "\\n");
}
function contentOf(message) {
  return typeof message?.content === "string"
    ? message.content
    : (message?.content || []).filter(part => part?.type === "text").map(part => part.text || "").join(" ");
}
function seed(file, id, marker) {
  if (existsSync(file)) return;
  const timestamp = new Date().toISOString();
  save(file, [
    { type: "session", version: 3, id, timestamp, cwd: process.cwd() },
    {
      type: "message",
      id: id + "-user",
      parentId: null,
      timestamp,
      message: { role: "user", content: marker },
    },
    {
      type: "message",
      id: id + "-assistant",
      parentId: id + "-user",
      timestamp,
      message: { role: "assistant", content: [{ type: "text", text: "reply-" + marker }] },
    },
  ]);
}

const endpoint = process.env.PI_ANDROID_ENDPOINT_KEY || "A";
const sessionDir = resolve(valueOf("--session-dir") || join(process.env.HOME, ".pi", "agent", "sessions"));
mkdirSync(sessionDir, { recursive: true });
const firstId = endpoint + "1";
const secondId = endpoint + "2";
const firstFile = join(sessionDir, firstId + ".jsonl");
const secondFile = join(sessionDir, secondId + ".jsonl");
const firstMarker = endpoint === "A" ? "AAA" : endpoint + "-first";
const secondMarker = endpoint === "A" ? "BBB" : endpoint + "-current";
seed(firstFile, firstId, firstMarker);
seed(secondFile, secondId, secondMarker);
let currentFile = valueOf("--session") || secondFile;
currentFile = resolve(currentFile);
if (!existsSync(currentFile)) throw new Error("selected session file does not exist: " + currentFile);
let entries = load(currentFile);
let sequence = 0;

function reload() {
  entries = load(currentFile);
}
function response(command, data = {}) {
  process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: true, data }) + "\\n");
}
function state() {
  const header = entries.find(entry => entry.type === "session");
  return {
    sessionId: header?.id || "",
    sessionFile: currentFile,
    isStreaming: false,
    isCompacting: false,
    messageCount: entries.filter(entry => entry.type === "message").length,
  };
}
function persistPrompt(message) {
  const header = entries.find(entry => entry.type === "session");
  const previous = entries.filter(entry => entry.type !== "session").at(-1)?.id || null;
  const userId = header.id + "-user-" + (++sequence);
  const assistantId = header.id + "-assistant-" + sequence;
  const timestamp = new Date().toISOString();
  entries.push({
    type: "message",
    id: userId,
    parentId: previous,
    timestamp,
    message: { role: "user", content: String(message || "") },
  });
  entries.push({
    type: "message",
    id: assistantId,
    parentId: userId,
    timestamp,
    message: { role: "assistant", content: [{ type: "text", text: "reply-" + String(message || "") }] },
  });
  save(currentFile, entries);
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
      response(command, state());
    } else if (command.type === "get_entries") {
      response(command, {
        leafId: entries.filter(entry => entry.type !== "session").at(-1)?.id || null,
        entries: entries.filter(entry => entry.type !== "session"),
      });
    } else if (command.type === "switch_session") {
      currentFile = resolve(String(command.sessionPath || ""));
      if (!existsSync(currentFile)) {
        process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: false, error: "session file not found" }) + "\\n");
      } else {
        reload();
        response(command, { cancelled: false });
      }
    } else if (command.type === "prompt") {
      persistPrompt(command.message);
      response(command);
      process.stdout.write(JSON.stringify({ type: "agent_start" }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text: "reply" }] } }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "agent_settled" }) + "\\n");
    } else if (command.type === "get_messages") {
      response(command, { messages: entries.filter(entry => entry.type === "message").map(entry => entry.message) });
    } else {
      response(command);
    }
  }
});
`);
await chmod(fakePi, 0o700);

const basePort = 31_000 + (process.pid % 500) * 2;
const records = ["A", "B", "C"].map((id, index) => ({
  id,
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
      PI_ANDROID_ENDPOINT_KEY: record.id,
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
  for (let attempt = 0; attempt < 120; attempt++) {
    try {
      const response = await request(record, "/health");
      if (response.status === 200) return response.json();
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw new Error(`bridge did not start on ${record.port}`);
}
async function startPi(record, extra = "") {
  const launchCommand = `pi --mode rpc --session-dir ${record.sessionDir} --session-id android-${record.id} ${extra}`.trim();
  const response = await request(record, "/start", {
    method: "POST",
    body: JSON.stringify({ cwd, launchCommand }),
  });
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return { launchCommand, state: JSON.parse(text).state };
}
async function stopBridge(instance) {
  await request(instance.record, "/shutdown", { method: "POST", body: "{}" }).catch(() => {});
  await new Promise(resolve => {
    if (instance.child.exitCode != null) return resolve();
    instance.child.once("exit", resolve);
    setTimeout(resolve, 1_000).unref?.();
  });
  instance.child.kill("SIGTERM");
}
async function history(record) {
  const response = await request(record, "/history");
  assert.equal(response.status, 200);
  return (await response.json()).history;
}
function userTexts(items) {
  return items.filter(item => item.role === "user").map(item => item.text);
}
async function switchPath(record, androidSessionId, targetPath) {
  const response = await request(record, "/switch-session", {
    method: "POST",
    body: JSON.stringify({
      androidSessionId,
      piConversationId: path.basename(targetPath, ".jsonl"),
      path: targetPath,
    }),
  });
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return JSON.parse(text);
}

let a = { record: records[0], ...startBridge(records[0]) };
let b = { record: records[1], ...startBridge(records[1]) };
let c = { record: records[2], ...startBridge(records[2]) };
try {
  await Promise.all(records.map(waitForHealth));
  const [aStart, bStart, cStart] = await Promise.all(records.map(record => startPi(record)));
  assert.equal(aStart.state.sessionId, "A2");
  assert.equal(bStart.state.sessionId, "B2");
  assert.equal(cStart.state.sessionId, "C2");
  assert.equal(path.basename(aStart.state.sessionFile), "A2.jsonl");
  assert.equal(path.basename(bStart.state.sessionFile), "B2.jsonl");
  assert.equal(path.basename(cStart.state.sessionFile), "C2.jsonl");

  const listed = await request(records[0], "/sessions").then(response => response.json());
  assert.deepEqual(
    listed.sessions.map(session => path.basename(session.path)).sort(),
    ["A1.jsonl", "A2.jsonl", "legacy.jsonl"],
    "/resume must list both private and legacy Pi sessions",
  );
  const a1Path = listed.sessions.find(session => path.basename(session.path) === "A1.jsonl").path;
  const a2Path = listed.sessions.find(session => path.basename(session.path) === "A2.jsonl").path;
  const listedLegacyPath = listed.sessions.find(session => path.basename(session.path) === "legacy.jsonl").path;
  assert.equal(listedLegacyPath, legacyPath);
  const wrongOwner = await request(records[0], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "B", piConversationId: "A1", path: a1Path }),
  });
  assert.equal(wrongOwner.status, 500, "a Bridge must reject a resume tagged for another Android Session");
  const wrongConversation = await request(records[0], "/switch-session", {
    method: "POST",
    body: JSON.stringify({
      androidSessionId: "A",
      piConversationId: "conversation-that-is-not-A1",
      path: a1Path,
    }),
  });
  assert.equal(wrongConversation.status, 500, "a Bridge must reject a mismatched Pi conversation ID before switching");
  assert.deepEqual(userTexts(await history(records[0])), ["BBB"]);
  const aHealthBefore = await request(records[0], "/health").then(response => response.json());
  const bHealthBefore = await request(records[1], "/health").then(response => response.json());
  const cHealthBefore = await request(records[2], "/health").then(response => response.json());

  const switchedA1 = await request(records[0], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "A", piConversationId: "A1", path: a1Path }),
  }).then(async response => {
    const text = await response.text();
    assert.equal(response.status, 200, text);
    return JSON.parse(text);
  });
  assert.equal(switchedA1.state.sessionId, "A1");
  assert.equal(switchedA1.state.sessionFile, a1Path);
  const aHealthAfterSwitch = await request(records[0], "/health").then(response => response.json());
  assert.equal(aHealthAfterSwitch.port, records[0].port);
  assert.equal(aHealthAfterSwitch.endpointKey, "A");
  assert.equal(aHealthAfterSwitch.bridgePid, aHealthBefore.bridgePid, "resume must not replace the Android Session Bridge");
  assert.equal(aHealthAfterSwitch.piPid, aHealthBefore.piPid, "resume must not replace the Pi process");
  assert.deepEqual(userTexts(await history(records[0])), ["AAA"], "A1 history must replace A2 history");

  await request(records[0], "/prompt", { method: "POST", body: JSON.stringify({ message: "after-A1" }) });
  assert.deepEqual(userTexts(await history(records[0])), ["AAA", "after-A1"]);

  const switchedA2 = await request(records[0], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "A", piConversationId: "A2", path: a2Path }),
  }).then(async response => {
    const text = await response.text();
    assert.equal(response.status, 200, text);
    return JSON.parse(text);
  });
  assert.equal(switchedA2.state.sessionId, "A2");
  assert.deepEqual(userTexts(await history(records[0])), ["BBB"], "A2 history must not contain A1 messages");

  const switchedLegacy = await request(records[0], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "A", piConversationId: "LEGACY", path: listedLegacyPath }),
  }).then(async response => {
    const text = await response.text();
    assert.equal(response.status, 200, text);
    return JSON.parse(text);
  });
  assert.equal(switchedLegacy.state.sessionId, "LEGACY");
  assert.deepEqual(userTexts(await history(records[0])), ["LEGACY"], "legacy history must replace the private conversation");
  await request(records[0], "/prompt", { method: "POST", body: JSON.stringify({ message: "after-legacy" }) });
  assert.deepEqual(userTexts(await history(records[0])), ["LEGACY", "after-legacy"]);

  const bHealthAfterSwitch = await request(records[1], "/health").then(response => response.json());
  assert.equal(bHealthAfterSwitch.port, bHealthBefore.port);
  assert.equal(bHealthAfterSwitch.bridgePid, bHealthBefore.bridgePid, "switching A must not replace B's Bridge");
  assert.equal(bHealthAfterSwitch.piPid, bHealthBefore.piPid, "switching A must not replace B's Pi");
  assert.deepEqual(userTexts(await history(records[1])), ["B-current"], "B remains independent while A resumes");
  const cHealthAfterA = await request(records[2], "/health").then(response => response.json());
  assert.equal(cHealthAfterA.port, cHealthBefore.port);
  assert.equal(cHealthAfterA.bridgePid, cHealthBefore.bridgePid, "switching A must not replace C's Bridge");
  assert.equal(cHealthAfterA.piPid, cHealthBefore.piPid, "switching A must not replace C's Pi");
  assert.deepEqual(userTexts(await history(records[2])), ["C-current"], "C remains independent while A resumes");

  const bSessions = await request(records[1], "/sessions").then(response => response.json());
  const cSessions = await request(records[2], "/sessions").then(response => response.json());
  const b1Path = bSessions.sessions.find(session => path.basename(session.path) === "B1.jsonl").path;
  const b2Path = bSessions.sessions.find(session => path.basename(session.path) === "B2.jsonl").path;
  const c1Path = cSessions.sessions.find(session => path.basename(session.path) === "C1.jsonl").path;
  const c2Path = cSessions.sessions.find(session => path.basename(session.path) === "C2.jsonl").path;
  const switchedB1 = await request(records[1], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "B", piConversationId: "B1", path: b1Path }),
  });
  assert.equal(switchedB1.status, 200);
  const switchedB1State = (await switchedB1.clone().json()).state;
  assert.equal(switchedB1State.sessionId, "B1");
  assert.equal(switchedB1State.sessionFile, b1Path);
  const bHealthAfterResume = await request(records[1], "/health").then(response => response.json());
  assert.equal(bHealthAfterResume.port, bHealthBefore.port);
  assert.equal(bHealthAfterResume.bridgePid, bHealthBefore.bridgePid);
  assert.equal(bHealthAfterResume.piPid, bHealthBefore.piPid);
  assert.deepEqual(userTexts(await history(records[1])), ["B-first"]);
  const switchedC1 = await request(records[2], "/switch-session", {
    method: "POST",
    body: JSON.stringify({ androidSessionId: "C", piConversationId: "C1", path: c1Path }),
  });
  assert.equal(switchedC1.status, 200);
  const switchedC1State = (await switchedC1.clone().json()).state;
  assert.equal(switchedC1State.sessionId, "C1");
  assert.equal(switchedC1State.sessionFile, c1Path);
  const cHealthAfterResume = await request(records[2], "/health").then(response => response.json());
  assert.equal(cHealthAfterResume.port, cHealthBefore.port);
  assert.equal(cHealthAfterResume.bridgePid, cHealthBefore.bridgePid);
  assert.equal(cHealthAfterResume.piPid, cHealthBefore.piPid);
  assert.deepEqual(userTexts(await history(records[2])), ["C-first"]);
  assert.deepEqual(userTexts(await history(records[0])), ["LEGACY", "after-legacy"], "B/C resume must not change A");

  const repeatedTargets = [
    [records[0], "A", a1Path, a2Path, aHealthBefore],
    [records[1], "B", b1Path, b2Path, bHealthBefore],
    [records[2], "C", c1Path, c2Path, cHealthBefore],
  ];
  for (let cycle = 0; cycle < 20; cycle++) {
    for (const [record, ownerId, firstPath, secondPath, baselineHealth] of repeatedTargets) {
      const targetPath = cycle % 2 === 0 ? firstPath : secondPath;
      const expectedConversationId = path.basename(targetPath, ".jsonl");
      const switched = await switchPath(record, ownerId, targetPath);
      assert.equal(switched.state.sessionId, expectedConversationId);
      assert.equal(switched.state.sessionFile, targetPath);
      const health = await request(record, "/health").then(response => response.json());
      assert.equal(health.port, baselineHealth.port);
      assert.equal(health.bridgePid, baselineHealth.bridgePid);
      assert.equal(health.piPid, baselineHealth.piPid);
    }
  }
  assert.deepEqual(userTexts(await history(records[0])), ["BBB"], "A repeated resume must stay on A's selected conversation");
  assert.deepEqual(userTexts(await history(records[1])), ["B-current"], "B repeated resume must stay isolated");
  assert.deepEqual(userTexts(await history(records[2])), ["C-current"], "C repeated resume must stay isolated");

  // Recreate only A's bridge with a launch selector for the selected legacy file.
  await stopBridge(a);
  a = { record: records[0], ...startBridge(records[0]) };
  await waitForHealth(records[0]);
  const recoveryLaunch = `--session ${listedLegacyPath}`;
  const restarted = await startPi(records[0], recoveryLaunch);
  assert.equal(restarted.state.sessionId, "LEGACY", "runtime restart must reopen the selected legacy conversation");
  assert.equal(restarted.state.sessionFile, listedLegacyPath);
  const aHealthAfterRestart = await request(records[0], "/health").then(response => response.json());
  assert.equal(aHealthAfterRestart.port, records[0].port);
  assert.notEqual(aHealthAfterRestart.bridgePid, aHealthBefore.bridgePid);
  assert.deepEqual(userTexts(await history(records[0])), ["LEGACY", "after-legacy"]);
  const bHealthAfterARebuild = await request(records[1], "/health").then(response => response.json());
  const cHealthAfterARebuild = await request(records[2], "/health").then(response => response.json());
  assert.equal(bHealthAfterARebuild.port, bHealthBefore.port);
  assert.equal(bHealthAfterARebuild.bridgePid, bHealthBefore.bridgePid);
  assert.equal(bHealthAfterARebuild.piPid, bHealthBefore.piPid);
  assert.equal(cHealthAfterARebuild.port, cHealthBefore.port);
  assert.equal(cHealthAfterARebuild.bridgePid, cHealthBefore.bridgePid);
  assert.equal(cHealthAfterARebuild.piPid, cHealthBefore.piPid);
  assert.deepEqual(userTexts(await history(records[1])), ["B-current"], "B must remain unaffected by A restart and resume");
  assert.deepEqual(userTexts(await history(records[2])), ["C-current"], "C must remain unaffected by A restart and resume");

  console.log("Mobile /resume conversation switching test passed");
} finally {
  await Promise.all([stopBridge(a), stopBridge(b), stopBridge(c)]);
  await rm(home, { recursive: true, force: true });
}
