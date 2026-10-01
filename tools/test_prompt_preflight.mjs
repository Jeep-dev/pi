import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

// Prompts that Pi answers late (pre-prompt compaction) or rejects during compaction must
// not surface as send failures, and Stop must hand back prompts the bridge still holds.
const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-preflight-"));
const prefix = path.join(home, "prefix");
const bin = path.join(prefix, "bin");
const port = 33_000 + (process.pid % 1_000);
const token = randomBytes(32).toString("base64url");
const cwd = path.join(home, "project");
await mkdir(bin, { recursive: true });
await mkdir(cwd, { recursive: true });
await mkdir(path.join(home, ".pi", "android"), { recursive: true });
const fakePi = path.join(bin, "pi");
await writeFile(fakePi, `
const { appendFileSync } = require("node:fs");
let buffer = "";
let compacting = false;
const received = process.cwd() + "/received.txt";
function emit(value) { process.stdout.write(JSON.stringify(value) + "\\n"); }
function ok(command, data = {}) { emit({ id: command.id, type: "response", command: command.type, success: true, data }); }
function fail(command, error) { emit({ id: command.id, type: "response", command: command.type, success: false, error }); }
function startCompaction(ms) {
  compacting = true;
  emit({ type: "compaction_start", reason: "threshold" });
  if (ms) setTimeout(endCompaction, ms);
}
function endCompaction() {
  if (!compacting) return;
  compacting = false;
  emit({ type: "compaction_end", reason: "threshold", result: {} });
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
      ok(command, { sessionId: "preflight", sessionFile: process.cwd() + "/s.jsonl", isStreaming: false, isCompacting: compacting, messageCount: 0 });
    } else if (command.type === "prompt") {
      if (command.message === "compact-briefly") { startCompaction(300); ok(command, { disposition: "started" }); continue; }
      if (command.message === "compact-forever") { startCompaction(0); ok(command, { disposition: "started" }); continue; }
      if (compacting) { fail(command, "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."); continue; }
      appendFileSync(received, command.message + "\\n");
      if (command.message === "slow-ok") setTimeout(() => ok(command, { disposition: "started" }), 400);
      else if (command.message === "slow-fail") setTimeout(() => fail(command, "provider exploded"), 400);
      else ok(command, { disposition: command.streamingBehavior ? "queued" : "started" });
    } else if (command.type === "clear_queue") {
      ok(command, { steering: ["queued-a"], followUp: ["later-b"] });
    } else if (command.type === "abort") {
      endCompaction();
      ok(command);
    } else if (command.type === "get_entries") {
      ok(command, { leafId: null, entries: [] });
    } else {
      ok(command);
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
    PI_ANDROID_PROMPT_ACK_MS: "150",
  },
  stdio: ["ignore", "pipe", "pipe"],
});
let diagnostics = "";
child.stdout.on("data", chunk => { diagnostics += chunk; });
child.stderr.on("data", chunk => { diagnostics += chunk; });
const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const request = (pathname, init = {}) => fetch(`http://127.0.0.1:${port}${pathname}`, { ...init, headers: { ...headers, ...(init.headers || {}) } });
const prompt = message => request("/prompt", { method: "POST", body: JSON.stringify({ message, streamingBehavior: "steer" }) });
const received = async () => (await readFile(path.join(cwd, "received.txt"), "utf8").catch(() => "")).split("\n").filter(Boolean);

try {
  let health;
  for (let attempt = 0; attempt < 80; attempt++) {
    try { health = await request("/health"); if (health.status === 200) break; } catch {}
    await wait(25);
  }
  assert.equal(health?.status, 200, diagnostics);
  const started = await request("/start", { method: "POST", body: JSON.stringify({ cwd, launchCommand: "pi --mode rpc" }) });
  assert.equal(started.status, 200, await started.text());

  // A slow preflight is reported as pending, not as a timeout.
  let response = await prompt("slow-ok");
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.disposition, "pending");

  // A late failure arrives as an event naming the message.
  response = await prompt("slow-fail");
  assert.equal((await response.json()).data.disposition, "pending");
  await wait(500);
  const events = await request("/events?after=0&wait=0").then(r => r.json());
  const failed = events.events.map(item => item.value).find(value => value?.type === "prompt_failed");
  assert.equal(failed?.message, "slow-fail");
  assert.match(failed?.error, /provider exploded/);

  // A prompt sent during compaction waits for it and then goes through.
  await (await prompt("compact-briefly")).text();
  response = await prompt("after-compaction");
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.disposition, "pending");
  await wait(400);
  assert.ok((await received()).includes("after-compaction"));
  const afterCompaction = await request("/events?after=0&wait=0").then(r => r.json());
  assert.ok(!afterCompaction.events.some(item => item.value?.type === "prompt_failed" && item.value.message === "after-compaction"));

  // Stop hands back the held prompt and Pi's cleared queue; the held prompt never runs.
  await (await prompt("compact-forever")).text();
  const held = prompt("held-during-compaction");
  await wait(250);
  const stop = await request("/stop", { method: "POST", body: "{}" }).then(r => r.json());
  assert.deepEqual(stop.restored.steering, ["held-during-compaction", "queued-a"]);
  assert.deepEqual(stop.restored.followUp, ["later-b"]);
  const heldResult = await held.then(r => r.json());
  assert.equal(heldResult.data.disposition, "pending");
  await wait(300);
  assert.ok(!(await received()).includes("held-during-compaction"), "a held prompt must not run after Stop");
  const after = await request("/events?after=0&wait=0").then(r => r.json());
  assert.ok(!after.events.some(item => item.value?.type === "prompt_failed" && item.value.message === "held-during-compaction"));
  console.log("Prompt preflight tests passed");
} finally {
  child.kill("SIGTERM");
  await wait(300);
  if (child.exitCode == null) child.kill("SIGKILL");
  await rm(home, { recursive: true, force: true });
}
