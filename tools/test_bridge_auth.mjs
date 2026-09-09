import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdir, mkdtemp, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-test-"));
const port = 20_000 + (process.pid % 20_000);
const token = randomBytes(32).toString("base64url");
await mkdir(path.join(home, ".pi", "android"), { recursive: true });

function waitForExit(child) {
  return new Promise(resolve => child.once("exit", (code, signal) => resolve({ code, signal })));
}

const child = spawn(process.execPath, [bridgePath], {
  env: { ...process.env, HOME: home, PI_ANDROID_PORT: String(port), PI_ANDROID_TOKEN: token },
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
  assert.equal(health.bridgeVersion, "2026-09-09.12");

  const missing = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(missing.status, 401);

  const wrong = await fetch(`http://127.0.0.1:${port}/health`, {
    headers: { Authorization: `Bearer ${randomBytes(32).toString("base64url")}` },
  });
  assert.equal(wrong.status, 401);

  await symlink("/etc/passwd", path.join(home, "outside-link"));
  const escaped = await fetch(`http://127.0.0.1:${port}/file?path=outside-link`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(escaped.status, 500);
  assert.match((await escaped.json()).error, /outside project/);

  const noToken = spawn(process.execPath, [bridgePath], {
    env: { ...process.env, HOME: home, PI_ANDROID_PORT: String(port + 1), PI_ANDROID_TOKEN: "" },
    stdio: "ignore",
  });
  const noTokenExit = await waitForExit(noToken);
  assert.notEqual(noTokenExit.code, 0, "bridge must refuse to start without authentication");

  console.log("Bridge authentication tests passed");
} finally {
  child.kill("SIGTERM");
  await Promise.race([waitForExit(child), new Promise(resolve => setTimeout(resolve, 2000))]);
  if (child.exitCode == null) child.kill("SIGKILL");
  await rm(home, { recursive: true, force: true });
}
