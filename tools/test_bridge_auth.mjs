import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { chmod, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const bridgePath = path.resolve("app/src/main/assets/pi-android-bridge.mjs");
const home = await mkdtemp(path.join(tmpdir(), "pi-android-test-"));
const port = 20_000 + (process.pid % 20_000);
const token = randomBytes(32).toString("base64url");
await mkdir(path.join(home, ".pi", "android"), { recursive: true });
const prefix = path.join(home, "prefix");
await mkdir(path.join(prefix, "bin"), { recursive: true });
const fakePi = path.join(prefix, "bin", "pi");
await writeFile(fakePi, `
process.on("SIGTERM", () => setTimeout(() => process.exit(0), 150));
let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const raw = buffer.slice(0, newline);
    buffer = buffer.slice(newline + 1);
    if (!raw) continue;
    const command = JSON.parse(raw);
    const data = command.type === "get_state"
      ? { sessionId: "test", isStreaming: false, isCompacting: false, messageCount: 0 }
      : {};
    const isImageTest = command.type === "prompt" && command.message.startsWith("__image_test__");
    const imageValid = !isImageTest ||
      (command.message.includes(".pi-android-uploads/") && command.images?.[0]?.type === "image" &&
        command.images[0].mimeType === "image/png" && command.images[0].data === "aGVsbG8=");
    process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: imageValid, data, ...(!imageValid ? { error: "image payload missing" } : {}) }) + "\\n");
  }
});
`);
await chmod(fakePi, 0o700);

function waitForExit(child) {
  return new Promise(resolve => child.once("exit", (code, signal) => resolve({ code, signal })));
}

const child = spawn(process.execPath, [bridgePath], {
  env: { ...process.env, HOME: home, PREFIX: prefix, PI_ANDROID_PORT: String(port), PI_ANDROID_TOKEN: token },
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
  assert.equal(health.bridgeVersion, "2026-09-10.5");

  const waitStarted = Date.now();
  const idleEvents = await fetch(`http://127.0.0.1:${port}/events?after=0&wait=120`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(idleEvents.status, 200);
  assert.ok(Date.now() - waitStarted >= 90, "event endpoint should long-poll while idle");

  const resetCursor = await fetch(`http://127.0.0.1:${port}/events?after=999999&wait=0`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(resetCursor.status, 200);
  assert.equal((await resetCursor.json()).gap, true, "a cursor ahead of a restarted bridge must trigger history recovery");

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
    env: { ...process.env, HOME: home, PREFIX: prefix, PI_ANDROID_PORT: String(port + 1), PI_ANDROID_TOKEN: "" },
    stdio: "ignore",
  });
  const noTokenExit = await waitForExit(noToken);
  assert.notEqual(noTokenExit.code, 0, "bridge must refuse to start without authentication");

  const startHeaders = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
  for (let attempt = 0; attempt < 2; attempt++) {
    const started = await fetch(`http://127.0.0.1:${port}/start`, {
      method: "POST",
      headers: startHeaders,
      body: JSON.stringify({ cwd: home, launchCommand: "pi --mode rpc" }),
    });
    assert.equal(started.status, 200, `Pi restart ${attempt + 1} failed: ${await started.text()}`);
  }
  await new Promise(resolve => setTimeout(resolve, 250));
  const imagePrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({
      message: "__image_test__",
      attachments: [{ name: "hello.png", mimeType: "image/png", data: "aGVsbG8=" }],
    }),
  });
  assert.equal(imagePrompt.status, 200, "image content must be forwarded to Pi RPC");

  const afterRestart = await fetch(`http://127.0.0.1:${port}/state`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(afterRestart.status, 200, "late exit from old Pi process must not detach the replacement process");

  const shutdown = await fetch(`http://127.0.0.1:${port}/shutdown`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(shutdown.status, 200);
  const stopped = await Promise.race([
    waitForExit(child),
    new Promise(resolve => setTimeout(() => resolve(null), 2000)),
  ]);
  assert.ok(stopped, "authenticated shutdown must stop the bridge");

  console.log("Bridge authentication tests passed");
} finally {
  child.kill("SIGTERM");
  await Promise.race([waitForExit(child), new Promise(resolve => setTimeout(resolve, 2000))]);
  if (child.exitCode == null) child.kill("SIGKILL");
  await rm(home, { recursive: true, force: true });
}
