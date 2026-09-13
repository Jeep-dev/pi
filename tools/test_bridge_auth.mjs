import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const bridgePath = path.resolve(__dirname, "../app/src/main/assets/pi-android-bridge.mjs");
const port = 28000 + Math.floor(Math.random() * 10000);
const token = "test-token-that-is-long-enough-for-auth-1234567890";
const endpointKey = "bridge-auth-test";
const fakeHome = await mkdtemp(path.join(tmpdir(), "pi-android-bridge-auth-"));
const fakeBin = path.join(fakeHome, "bin");
await import("node:fs/promises").then(fs => fs.mkdir(fakeBin, { recursive: true }));
const fakePi = path.join(fakeBin, "pi");
await writeFile(fakePi, "#!/bin/sh\nexit 0\n", { mode: 0o755 });

const child = spawn(process.execPath, [bridgePath], {
  env: {
    ...process.env,
    PI_ANDROID_PORT: String(port),
    PI_ANDROID_TOKEN: token,
    PI_ANDROID_ENDPOINT_KEY: endpointKey,
    HOME: fakeHome,
    PREFIX: fakeHome,
    PATH: `${fakeBin}:${process.env.PATH || ""}`,
    PI_ANDROID_MAX_EVENT_BYTES: String(1024 * 1024),
  },
  stdio: ["ignore", "pipe", "pipe"],
});
let diagnostics = "";
child.stdout.on("data", chunk => { diagnostics += chunk; });
child.stderr.on("data", chunk => { diagnostics += chunk; });

try {
  let authorized;
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      authorized = await fetch(`http://127.0.0.1:${port}/health`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      break;
    } catch {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
  }
  assert.ok(authorized, `bridge did not start: ${diagnostics}`);
  assert.equal(authorized.status, 200);
  const health = await authorized.json();
  assert.equal(health.bridgeVersion, "2026-09-13.1");
  assert.ok(health.capabilities.includes("file-reference-v1"));
  assert.ok(health.capabilities.includes("durable-history-v1"));
  assert.ok(health.capabilities.includes("recovery-snapshot-v1"));
  assert.ok(health.capabilities.includes("persistent-widgets-v1"));
  assert.ok(health.capabilities.includes("multi-session-v1"));
  assert.ok(health.capabilities.includes("consistent-recovery-v1"));
  assert.ok(health.capabilities.includes("bounded-event-cache-v1"));
  assert.ok(health.capabilities.includes("hard-stop-v1"));
  assert.ok(health.capabilities.includes("conversation-owner-v1"));
  assert.ok(health.capabilities.includes("tool-history-metadata-v1"));

  const waitStarted = Date.now();
  const idleEvents = await fetch(`http://127.0.0.1:${port}/events?after=0&wait=120`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(idleEvents.status, 200);
  const idleBody = await idleEvents.json();
  assert.ok(Date.now() - waitStarted >= 80, "long poll should wait while idle");
  assert.ok(Array.isArray(idleBody.events));

  const noAuth = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(noAuth.status, 401);

  const wrongAuth = await fetch(`http://127.0.0.1:${port}/health`, {
    headers: { Authorization: "Bearer wrong-token" },
  });
  assert.equal(wrongAuth.status, 401);

  const shutdown = await fetch(`http://127.0.0.1:${port}/shutdown`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: "{}",
  });
  assert.equal(shutdown.status, 200);
} finally {
  child.kill("SIGTERM");
  await new Promise(resolve => {
    if (child.exitCode != null) return resolve();
    child.once("exit", resolve);
    setTimeout(resolve, 1000);
  });
  await rm(fakeHome, { recursive: true, force: true });
}
