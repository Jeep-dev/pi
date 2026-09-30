import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

// A huge single-line RPC reply must not block /health (it looked like a frozen
// Termux and got Pi restarted), and a second Bridge on a taken port must fail
// loudly instead of exiting 0 while the app waits for it.
const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-resilience-"));
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
    else if (command.type === "get_entries") {
      const text = "长".repeat(4_000);
      const entries = Array.from({ length: 1_500 }, (_, i) => ({ type: "message", id: "e" + i, parentId: i ? "e" + (i - 1) : null, timestamp: new Date(0).toISOString(), message: { role: i % 2 ? "assistant" : "user", content: [{ type: "text", text }] } }));
      reply({ entries, leafId: "e1499" });
    } else reply({});
  }
});
`);
await chmod(fakePi, 0o700);

const token = randomBytes(32).toString("base64url");
const port = 34_000 + (process.pid % 900);
const env = { ...process.env, HOME: home, PREFIX: path.join(home, "prefix"), PI_ANDROID_PORT: String(port), PI_ANDROID_TOKEN: token, PI_ANDROID_PID_FILE: path.join(home, "bridge.pid") };
const bridge = spawn(process.execPath, [bridgePath], { env, stdio: ["ignore", "pipe", "pipe"] });
let diagnostics = "";
bridge.stdout.on("data", chunk => { diagnostics += chunk; });
bridge.stderr.on("data", chunk => { diagnostics += chunk; });
const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
const call = (pathname, init = {}) => fetch(`http://127.0.0.1:${port}${pathname}`, { ...init, headers: { ...auth, ...(init.headers || {}) } });

try {
  for (let attempt = 0; ; attempt++) {
    try { if ((await call("/health")).status === 200) break; } catch {}
    assert.ok(attempt < 200, `bridge did not start\n${diagnostics}`);
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  const started = await call("/start", { method: "POST", body: JSON.stringify({ cwd: home, launchCommand: "pi --mode rpc" }) });
  assert.equal(started.status, 200, await started.text());

  // ~36 MB single JSON line from pi.
  let worst = 0;
  let probing = true;
  const probes = (async () => {
    while (probing) {
      const began = Date.now();
      assert.equal((await call("/health")).status, 200);
      worst = Math.max(worst, Date.now() - began);
      await new Promise(resolve => setTimeout(resolve, 20));
    }
  })();
  const snapshot = await call("/snapshot");
  assert.equal(snapshot.status, 200, `snapshot failed\n${diagnostics}`);
  const body = await snapshot.json();
  probing = false;
  await probes;
  assert.ok(Array.isArray(body.history) && body.history.length > 0, "snapshot must carry the history");
  assert.ok(worst < 2_000, `/health stalled ${worst}ms behind a large RPC reply`);

  // Second Bridge on the same port: explicit failure, not a silent exit 0.
  const duplicate = spawn(process.execPath, [bridgePath], { env: { ...env, PI_ANDROID_PID_FILE: path.join(home, "dup.pid") }, stdio: ["ignore", "pipe", "pipe"] });
  let duplicateLog = "";
  duplicate.stderr.on("data", chunk => { duplicateLog += chunk; });
  const code = await new Promise(resolve => duplicate.on("exit", resolve));
  assert.equal(code, 98, `duplicate Bridge should exit 98\n${duplicateLog}`);
  assert.match(duplicateLog, /listen failed: EADDRINUSE/);
  assert.equal((await call("/health")).status, 200, "the running Bridge must be untouched");
  console.log(`Bridge resilience tests passed (worst /health ${worst}ms)`);
} finally {
  await call("/shutdown", { method: "POST", body: "{}" }).catch(() => {});
  bridge.kill("SIGTERM");
  await rm(home, { recursive: true, force: true });
}
