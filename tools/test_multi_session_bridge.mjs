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
const fakePi = path.join(bin, "pi");
const cwdA = path.join(home, "project-a");
const cwdB = path.join(home, "project-b");
await mkdir(bin, { recursive: true });
await mkdir(cwdA, { recursive: true });
await mkdir(cwdB, { recursive: true });
await writeFile(fakePi, `
let buffer = "";
const sessionFile = process.env.FAKE_SESSION_FILE || "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const command = JSON.parse(buffer.slice(0, newline));
    buffer = buffer.slice(newline + 1);
    let data = {};
    if (command.type === "get_state") {
      data = { sessionId: process.cwd(), sessionFile, isStreaming: false, isCompacting: false, messageCount: 0 };
    }
    if (command.type === "prompt") {
      process.stdout.write(JSON.stringify({ type: "stderr", text: process.cwd() }) + "\\n");
    }
    process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: true, data }) + "\\n");
  }
});
process.on("SIGTERM", () => process.exit(0));
`);
await chmod(fakePi, 0o700);

const tokenA = randomBytes(32).toString("base64url");
const tokenB = randomBytes(32).toString("base64url");
const portA = 30_000 + (process.pid % 1_000) * 2;
const portB = portA + 1;
const pidFileA = path.join(home, ".pi", "android", "sessions", "a", "bridge.pid");
const pidFileB = path.join(home, ".pi", "android", "sessions", "b", "bridge.pid");
await mkdir(path.dirname(pidFileA), { recursive: true });
await mkdir(path.dirname(pidFileB), { recursive: true });

function startBridge(port, token, pidFile) {
  const child = spawn(process.execPath, [bridgePath], {
    env: {
      ...process.env,
      HOME: home,
      PREFIX: prefix,
      PI_ANDROID_PORT: String(port),
      PI_ANDROID_TOKEN: token,
      PI_ANDROID_PID_FILE: pidFile,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostics = "";
  child.stdout.on("data", chunk => { diagnostics += chunk; });
  child.stderr.on("data", chunk => { diagnostics += chunk; });
  return { child, diagnostics: () => diagnostics };
}

async function waitForHealth(port, token) {
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (response.status === 200) return response;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 30));
  }
  throw new Error(`bridge did not start on ${port}`);
}

function headers(token) {
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

async function startPi(port, token, cwd) {
  const response = await fetch(`http://127.0.0.1:${port}/start`, {
    method: "POST",
    headers: headers(token),
    body: JSON.stringify({ cwd, launchCommand: "pi --mode rpc" }),
  });
  assert.equal(response.status, 200, await response.text());
}

const bridgeA = startBridge(portA, tokenA, pidFileA);
const bridgeB = startBridge(portB, tokenB, pidFileB);
try {
  await waitForHealth(portA, tokenA);
  await waitForHealth(portB, tokenB);
  await startPi(portA, tokenA, cwdA);
  await startPi(portB, tokenB, cwdB);

  const healthA = await fetch(`http://127.0.0.1:${portA}/health`, { headers: headers(tokenA) }).then(response => response.json());
  const healthB = await fetch(`http://127.0.0.1:${portB}/health`, { headers: headers(tokenB) }).then(response => response.json());
  assert.equal(healthA.piRunning, true);
  assert.equal(healthB.piRunning, true);
  assert.equal(healthA.cwd, cwdA);
  assert.equal(healthB.cwd, cwdB);
  assert.notEqual(healthA.cwd, healthB.cwd);

  for (const [port, token, cwd] of [[portA, tokenA, cwdA], [portB, tokenB, cwdB]]) {
    const prompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
      method: "POST",
      headers: headers(token),
      body: JSON.stringify({ message: "parallel probe" }),
    });
    assert.equal(prompt.status, 200);
    await new Promise(resolve => setTimeout(resolve, 40));
    const events = await fetch(`http://127.0.0.1:${port}/events?after=0&wait=0`, {
      headers: headers(token),
    }).then(response => response.json());
    assert.ok(events.events.some(item => item.value?.text === cwd), `missing isolated event for ${cwd}`);
  }

  const shutdownB = await fetch(`http://127.0.0.1:${portB}/shutdown`, {
    method: "POST",
    headers: headers(tokenB),
  });
  assert.equal(shutdownB.status, 200);
  await new Promise(resolve => setTimeout(resolve, 150));
  const healthAAfterB = await fetch(`http://127.0.0.1:${portA}/health`, { headers: headers(tokenA) }).then(response => response.json());
  assert.equal(healthAAfterB.piRunning, true, "stopping one endpoint must not stop the other Pi");

  const pidA = await readFile(pidFileA, "utf8").catch(() => "");
  assert.ok(pidA.trim(), "each endpoint must have its own pid file");
  console.log("Multi-session bridge isolation test passed");
} finally {
  for (const [port, token, bridge] of [[portA, tokenA, bridgeA], [portB, tokenB, bridgeB]]) {
    await fetch(`http://127.0.0.1:${port}/shutdown`, { method: "POST", headers: headers(token) }).catch(() => {});
    bridge.child.kill("SIGTERM");
  }
  await rm(home, { recursive: true, force: true });
}
