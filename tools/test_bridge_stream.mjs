import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

// Push transport guarantees: first frame on connect, live delivery, resume by
// cursor without duplicates, heartbeats while idle, and a client disconnect
// never touches the Pi process.
const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-stream-"));
const bin = path.join(home, "prefix", "bin");
await mkdir(bin, { recursive: true });
const fakePi = path.join(bin, "pi");
await writeFile(fakePi, `
process.on("SIGTERM", () => process.exit(0));
let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const command = JSON.parse(buffer.slice(0, newline));
    buffer = buffer.slice(newline + 1);
    const reply = data => process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: true, data }) + "\\n");
    if (command.type === "get_state") reply({ sessionId: "S", sessionFile: "", isStreaming: false, isCompacting: false, messageCount: 0 });
    else if (command.type === "prompt") {
      reply({});
      process.stdout.write(JSON.stringify({ type: "agent_start" }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text: "reply:" + command.message }] } }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "agent_settled" }) + "\\n");
    } else reply({});
  }
});
`);
await chmod(fakePi, 0o700);

const token = randomBytes(32).toString("base64url");
const port = 33_000 + (process.pid % 900);
const bridge = spawn(process.execPath, [bridgePath], {
  env: { ...process.env, HOME: home, PREFIX: path.join(home, "prefix"), PI_ANDROID_PORT: String(port), PI_ANDROID_TOKEN: token, PI_ANDROID_PID_FILE: path.join(home, "bridge.pid") },
  stdio: ["ignore", "pipe", "pipe"],
});
let diagnostics = "";
bridge.stdout.on("data", chunk => { diagnostics += chunk; });
bridge.stderr.on("data", chunk => { diagnostics += chunk; });

const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
const call = (pathname, init = {}) => fetch(`http://127.0.0.1:${port}${pathname}`, { ...init, headers: { ...auth, ...(init.headers || {}) } });

/** Open /stream and collect frames and heartbeats until closed. */
function openStream(after) {
  const controller = new AbortController();
  const frames = [];
  let pings = 0;
  let waiters = [];
  const notify = () => { waiters.forEach(wake => wake()); waiters = []; };
  const done = (async () => {
    const response = await call(`/stream?after=${after}`, { signal: controller.signal });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") || "", /text\/event-stream/);
    const decoder = new TextDecoder();
    let text = "";
    try {
      for await (const chunk of response.body) {
        text += decoder.decode(chunk, { stream: true });
        let split;
        while ((split = text.indexOf("\n\n")) >= 0) {
          const block = text.slice(0, split);
          text = text.slice(split + 2);
          if (block.startsWith(":")) pings++;
          else if (block.startsWith("data:")) frames.push(JSON.parse(block.slice(5).trim()));
          notify();
        }
      }
    } catch (error) {
      if (error.name !== "AbortError") throw error;
    }
  })();
  const until = async (predicate, timeoutMs, label) => {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
      const left = deadline - Date.now();
      assert.ok(left > 0, `timed out waiting for ${label}`);
      await new Promise(resolve => { waiters.push(resolve); setTimeout(resolve, Math.min(left, 200)); });
    }
  };
  return { frames, pings: () => pings, until, close: () => controller.abort(), done };
}

const types = frames => frames.flatMap(frame => frame.events.map(event => event.value?.type));

try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await call("/health")).status === 200) break; } catch {}
    assert.ok(attempt < 200, `bridge did not start\n${diagnostics}`);
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  const started = await call("/start", { method: "POST", body: JSON.stringify({ cwd: home, launchCommand: "pi --mode rpc" }) });
  assert.equal(started.status, 200, await started.text());
  const piPid = (await (await call("/health")).json()).piPid;

  const first = openStream(0);
  await first.until(() => first.frames.length >= 1, 3_000, "first frame");
  assert.equal(first.frames[0].gap, false);

  assert.equal((await call("/prompt", { method: "POST", body: JSON.stringify({ message: "one" }) })).status, 200);
  await first.until(() => types(first.frames).includes("agent_settled"), 3_000, "live agent events");
  const cursor = first.frames.at(-1).latest;
  first.close();
  await first.done;

  // Events produced while no client is attached must be replayed exactly once.
  assert.equal((await call("/prompt", { method: "POST", body: JSON.stringify({ message: "two" }) })).status, 200);
  await new Promise(resolve => setTimeout(resolve, 100));
  const resumed = openStream(cursor);
  await resumed.until(() => types(resumed.frames).includes("agent_settled"), 3_000, "replayed events");
  const replayed = resumed.frames.flatMap(frame => frame.events);
  assert.ok(replayed.every(event => event.seq > cursor), "resume must not repeat delivered events");
  assert.equal(new Set(replayed.map(event => event.seq)).size, replayed.length, "no duplicate sequence numbers");
  assert.ok(replayed.some(event => JSON.stringify(event.value).includes("reply:two")));

  // An idle stream stays open and sends heartbeats instead of timing out.
  await resumed.until(() => resumed.pings() >= 1, 12_000, "heartbeat");
  resumed.close();
  await resumed.done;

  const health = await (await call("/health")).json();
  assert.equal(health.piRunning, true, "closing streams must not stop Pi");
  assert.equal(health.piPid, piPid, "closing streams must not restart Pi");
  assert.ok(health.capabilities.includes("sse-stream-v1"));
  console.log("Bridge stream tests passed");
} finally {
  await call("/shutdown", { method: "POST", body: "{}" }).catch(() => {});
  bridge.kill("SIGTERM");
  await rm(home, { recursive: true, force: true });
}
