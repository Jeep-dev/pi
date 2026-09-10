import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
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
    let data = {};
    if (command.type === "get_state") {
      data = { sessionId: "test", isStreaming: false, isCompacting: false, messageCount: 4 };
    } else if (command.type === "get_entries") {
      data = {
        leafId: "final",
        entries: [
          { type: "message", id: "user", parentId: null, message: { role: "user", content: [{ type: "text", text: "run checks" }] } },
          { type: "message", id: "call", parentId: "user", message: { role: "assistant", content: [{ type: "thinking", thinking: "Checking" }, { type: "toolCall", id: "tool-1", name: "bash", arguments: { command: "npm test" } }], stopReason: "toolUse" } },
          { type: "message", id: "result", parentId: "call", message: { role: "toolResult", toolCallId: "tool-1", toolName: "bash", content: [{ type: "text", text: "all tests passed" }], isError: false } },
          { type: "message", id: "final", parentId: "result", message: { role: "assistant", content: [{ type: "text", text: "Done" }], stopReason: "stop" } },
        ],
      };
    }
    if (command.type === "prompt" && command.message === "__tree_test__") {
      process.stdout.write(JSON.stringify({ type: "extension_ui_request", id: "tree-dialog", method: "select", title: "Session Tree", options: ["root", "leaf"] }) + "\\n");
    }
    const isAttachmentTest = command.type === "prompt" && command.message.startsWith("__attachment_test__");
    const attachmentValid = !isAttachmentTest || command.message.includes(".pi-android-uploads/");
    process.stdout.write(JSON.stringify({ id: command.id, type: "response", command: command.type, success: attachmentValid, data, ...(!attachmentValid ? { error: "file reference missing" } : {}) }) + "\\n");
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
  assert.equal(health.bridgeVersion, "2026-09-10.8");
  assert.ok(health.capabilities.includes("file-reference-v1"));
  assert.ok(health.capabilities.includes("durable-history-v1"));
  assert.ok(health.capabilities.includes("recovery-snapshot-v1"));

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
  const upload = await fetch(`http://127.0.0.1:${port}/upload?name=huge-reference.bin`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/octet-stream" },
    body: Buffer.from("streamed-file"),
    duplex: "half",
  });
  assert.equal(upload.status, 200);
  const uploaded = await upload.json();
  assert.equal(await readFile(path.join(home, uploaded.path), "utf8"), "streamed-file");

  const attachmentPrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({
      message: "__attachment_test__",
      attachments: [{ name: "huge-reference.bin", path: uploaded.path, mimeType: "application/octet-stream", byteCount: 13 }],
    }),
  });
  assert.equal(attachmentPrompt.status, 200, "Pi RPC must receive the file path instead of embedded file bytes");

  const reference = await fetch(`http://127.0.0.1:${port}/reference?path=${encodeURIComponent(fakePi)}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(reference.status, 200, "directly readable files should be referenced without copying");
  assert.equal((await reference.json()).path, fakePi);

  const afterRestart = await fetch(`http://127.0.0.1:${port}/state`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(afterRestart.status, 200, "late exit from old Pi process must not detach the replacement process");

  const treePrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "__tree_test__" }),
  });
  assert.equal(treePrompt.status, 200);
  const snapshotResponse = await fetch(`http://127.0.0.1:${port}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(snapshotResponse.status, 200);
  const snapshot = await snapshotResponse.json();
  assert.deepEqual(snapshot.history.map(item => item.role), ["user", "thinking", "tool", "assistant"]);
  assert.match(snapshot.history.find(item => item.role === "tool").text, /npm test[\s\S]*all tests passed/,
    "completed tool calls and output must survive UI process restart");
  assert.equal(snapshot.pendingUi[0]?.id, "tree-dialog", "an open /tree selector must survive UI process restart");

  const closeTree = await fetch(`http://127.0.0.1:${port}/extension-ui`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ id: "tree-dialog", cancelled: true }),
  });
  assert.equal(closeTree.status, 200);

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
