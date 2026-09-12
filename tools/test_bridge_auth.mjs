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
let pendingTreeCommand = "";
let snapshotMode = "normal";
let activeSessionFile = process.env.FAKE_SESSION_FILE || "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  while (buffer.includes("\\n")) {
    const newline = buffer.indexOf("\\n");
    const raw = buffer.slice(0, newline);
    buffer = buffer.slice(newline + 1);
    if (!raw) continue;
    const command = JSON.parse(raw);
    if (command.type === "extension_ui_response" && command.id === "async-tree-dialog") {
      if (pendingTreeCommand) {
        process.stdout.write(JSON.stringify({ id: pendingTreeCommand, type: "response", command: "prompt", success: true }) + "\\n");
        pendingTreeCommand = "";
      }
      continue;
    }
    if (command.type === "prompt" && command.message === "/tree-pending") {
      pendingTreeCommand = command.id;
      process.stdout.write(JSON.stringify({ type: "extension_ui_request", id: "async-tree-dialog", method: "select", title: "Session Tree", options: ["◆ latest"] }) + "\\n");
      continue;
    }
    if (command.type === "prompt" && command.message === "__snapshot_active__") {
      snapshotMode = "active";
      const activeEvents = [
        { type: "agent_start" },
        { type: "message_start", message: { role: "assistant", content: [] } },
        { type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "completed duplicate" } },
        { type: "message_end", message: { role: "assistant", content: [{ type: "toolCall", id: "already-durable", name: "read", arguments: { path: "done.txt" } }, { type: "toolCall", id: "not-yet-durable", name: "write", arguments: { path: "pending.txt", content: "pending" } }], stopReason: "toolUse" } },
        { type: "tool_execution_start", toolCallId: "already-durable", toolName: "read", args: { path: "done.txt" } },
        { type: "tool_execution_end", toolCallId: "already-durable", toolName: "read", result: { content: [{ type: "text", text: "durable result" }] }, isError: false },
        { type: "tool_execution_start", toolCallId: "not-yet-durable", toolName: "write", args: { path: "pending.txt", content: "pending" } },
        { type: "tool_execution_end", toolCallId: "not-yet-durable", toolName: "write", result: { content: [{ type: "text", text: "pending result" }] }, isError: false },
        { type: "message_start", message: { role: "assistant", content: [] } },
        { type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "live partial" } },
        { type: "queue_update", steering: ["queued after restart"], followUp: [] },
      ];
      process.stdout.write(activeEvents.map(event => JSON.stringify(event) + "\\n").join(""));
    }
    if (command.type === "prompt" && command.message === "__snapshot_race__") {
      snapshotMode = "race";
      const raceEvents = [
        { type: "agent_settled" },
        { type: "agent_start" },
        { type: "message_start", message: { role: "assistant", content: [] } },
        { type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "race partial" } },
      ];
      process.stdout.write(raceEvents.map(event => JSON.stringify(event) + "\\n").join(""));
    }
    if (command.type === "prompt" && command.message === "__event_pressure__") {
      const payload = "x".repeat(100_000);
      const pressureEvents = [
        { type: "queue_update", steering: ["survives cache pressure"], followUp: [] },
        ...Array.from({ length: 20 }, (_, index) => ({ type: "stderr", text: payload + index })),
      ];
      process.stdout.write(pressureEvents.map(event => JSON.stringify(event) + "\\n").join(""));
    }
    let data = {};
    if (command.type === "switch_session") activeSessionFile = String(command.sessionPath || "");
    if (command.type === "get_state") {
      data = { sessionId: "test", sessionFile: activeSessionFile, isStreaming: false, isCompacting: false, messageCount: 4 };
    } else if (command.type === "get_entries" && snapshotMode === "active") {
      data = {
        leafId: "active-result",
        entries: [
          { type: "message", id: "active-user", parentId: null, message: { role: "user", content: [{ type: "text", text: "active request" }] } },
          { type: "message", id: "active-call", parentId: "active-user", message: { role: "assistant", content: [{ type: "toolCall", id: "already-durable", name: "read", arguments: { path: "done.txt" } }, { type: "toolCall", id: "not-yet-durable", name: "write", arguments: { path: "pending.txt", content: "pending" } }], stopReason: "toolUse" } },
          { type: "message", id: "active-result", parentId: "active-call", message: { role: "toolResult", toolCallId: "already-durable", toolName: "read", content: [{ type: "text", text: "durable result" }], isError: false } },
        ],
      };
    } else if (command.type === "get_entries" && snapshotMode === "race") {
      data = {
        leafId: "race-user",
        entries: [
          { type: "message", id: "race-user", parentId: null, message: { role: "user", content: [{ type: "text", text: "race request" }] } },
        ],
      };
      snapshotMode = "normal";
      const response = { id: command.id, type: "response", command: command.type, success: true, data };
      const eventsAfterResponse = [
        { type: "message_end", message: { role: "assistant", content: [{ type: "text", text: "race final" }], stopReason: "stop" } },
        { type: "agent_end", messages: [], willRetry: false },
        { type: "agent_settled" },
      ];
      process.stdout.write(JSON.stringify(response) + "\\n" + eventsAfterResponse.map(event => JSON.stringify(event) + "\\n").join(""));
      continue;
    } else if (command.type === "get_entries") {
      data = {
        leafId: "post-final",
        entries: [
          { type: "message", id: "summarized-old", parentId: null, message: { role: "user", content: [{ type: "text", text: "obsolete original history" }] } },
          { type: "message", id: "user", parentId: "summarized-old", message: { role: "user", content: [{ type: "text", text: "run checks" }] } },
          { type: "message", id: "call", parentId: "user", message: { role: "assistant", content: [{ type: "thinking", thinking: "Checking" }, { type: "toolCall", id: "tool-1", name: "bash", arguments: { command: "npm test" } }, { type: "toolCall", id: "tool-edit", name: "edit", arguments: { path: "src/example.kt", edits: [{ oldText: "old", newText: "new" }] } }], stopReason: "toolUse" } },
          { type: "message", id: "result", parentId: "call", message: { role: "toolResult", toolCallId: "tool-1", toolName: "bash", content: [{ type: "text", text: "all tests passed" }], isError: false } },
          { type: "message", id: "edit-result", parentId: "result", message: { role: "toolResult", toolCallId: "tool-edit", toolName: "edit", content: [{ type: "text", text: "Successfully applied 1 edit" }], details: { diff: " 1 unchanged\\n-2 old\\n+2 new\\n 3 context\\n 4 context\\n 5 context\\n 6 context\\n 7 context\\n 8 context\\n 9 context\\n 10 context\\n 11 final" }, isError: false } },
          { type: "message", id: "kept-final", parentId: "edit-result", message: { role: "assistant", content: [{ type: "text", text: "Checks done" }], stopReason: "stop" } },
          { type: "compaction", id: "compact", parentId: "kept-final", summary: "Actual compact summary", firstKeptEntryId: "user", tokensBefore: 136424 },
          { type: "message", id: "post-user", parentId: "compact", message: { role: "user", content: [{ type: "text", text: "continue" }] } },
          { type: "message", id: "post-final", parentId: "post-user", message: { role: "assistant", content: [{ type: "text", text: "Done" }], stopReason: "stop" } },
        ],
      };
    }
    if (command.type === "prompt" && command.message === "__tree_test__") {
      process.stdout.write(JSON.stringify({ type: "extension_ui_request", id: "tree-dialog", method: "select", title: "Session Tree", options: ["root", "leaf"] }) + "\\n");
    }
    if (command.type === "prompt" && command.message === "__widget_test__") {
      process.stdout.write(JSON.stringify({ type: "extension_ui_request", id: "extensions-widget", method: "setWidget", widgetKey: "__android_loaded_extensions", widgetLines: ["mobile.ts", "project.ts"] }) + "\\n");
      process.stdout.write(JSON.stringify({ type: "extension_ui_request", id: "resources-widget", method: "setWidget", widgetKey: "__android_loaded_resources", widgetLines: ["[Context]", "  AGENTS.md", "[Prompts]", "  /review"] }) + "\\n");
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
  env: {
    ...process.env,
    HOME: home,
    PREFIX: prefix,
    PI_ANDROID_PORT: String(port),
    PI_ANDROID_TOKEN: token,
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
  assert.equal(health.bridgeVersion, "2026-09-12.2");
  assert.ok(health.capabilities.includes("file-reference-v1"));
  assert.ok(health.capabilities.includes("durable-history-v1"));
  assert.ok(health.capabilities.includes("recovery-snapshot-v1"));
  assert.ok(health.capabilities.includes("persistent-widgets-v1"));
  assert.ok(health.capabilities.includes("multi-session-v1"));
  assert.ok(health.capabilities.includes("consistent-recovery-v1"));
  assert.ok(health.capabilities.includes("bounded-event-cache-v1"));
  assert.ok(health.capabilities.includes("hard-stop-v1"));

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

  const sessionDirectoryName = `--${path.resolve(home).replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--`;
  const sessionDirectory = path.join(home, ".pi", "agent", "sessions", sessionDirectoryName);
  const activeSession = path.join(sessionDirectory, "active.jsonl");
  await mkdir(sessionDirectory, { recursive: true });
  await writeFile(activeSession, "{}\n");
  await symlink("/etc/passwd", path.join(sessionDirectory, "leak.jsonl"));
  const listedSessions = await fetch(`http://127.0.0.1:${port}/sessions`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.ok(
    !listedSessions.sessions.some(session => session.path.endsWith("leak.jsonl")),
    "session discovery must not follow symlinks outside the session directory",
  );
  const switched = await fetch(`http://127.0.0.1:${port}/switch-session`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ path: activeSession }),
  });
  assert.equal(switched.status, 200, `session switch failed: ${await switched.text()}`);
  const switchedHealth = await fetch(`http://127.0.0.1:${port}/health`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.equal(switchedHealth.activeSessionFile, activeSession, "bridge health must track the actually active session");

  const commandStarted = Date.now();
  const detachedCommand = await fetch(`http://127.0.0.1:${port}/command`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "/tree-pending" }),
  });
  assert.equal(detachedCommand.status, 202);
  assert.ok(Date.now() - commandStarted < 1000, "interactive slash commands must not wait for dialog completion");
  await new Promise(resolve => setTimeout(resolve, 50));
  const commandEvents = await fetch(`http://127.0.0.1:${port}/events?after=0&wait=0`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.ok(commandEvents.events.some(item => item.value?.id === "async-tree-dialog"), "async tree dialog was not forwarded");
  const completeDetachedCommand = await fetch(`http://127.0.0.1:${port}/extension-ui`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ id: "async-tree-dialog", value: "◆ latest" }),
  });
  assert.equal(completeDetachedCommand.status, 200);

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

  const largePreviewPath = path.join(home, "large-preview.txt");
  await writeFile(largePreviewPath, "a".repeat(1_999_999) + "€tail");
  const largePreview = await fetch(`http://127.0.0.1:${port}/file?path=large-preview.txt`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.equal(largePreview.truncated, true, "large editor files must be marked as truncated so Android cannot overwrite them");
  assert.equal(largePreview.content.length, 1_999_999, "the file endpoint must read only its bounded preview");
  assert.ok(!largePreview.content.includes("�"), "a byte-bounded UTF-8 preview must not end in a replacement character");

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
  const widgetPrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "__widget_test__" }),
  });
  assert.equal(widgetPrompt.status, 200);
  const snapshotResponse = await fetch(`http://127.0.0.1:${port}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(snapshotResponse.status, 200);
  const snapshot = await snapshotResponse.json();
  assert.deepEqual(
    snapshot.history.map(item => item.role),
    ["compaction", "user", "thinking", "tool", "tool", "assistant", "user", "assistant"],
    "history must match Pi's active compaction-aware context",
  );
  assert.equal(snapshot.history[0].text, "Actual compact summary", "the real compaction summary must be available to Android");
  assert.equal(snapshot.history[0].tokensBefore, 136424);
  assert.equal(snapshot.history[0].collapsed, true);
  assert.ok(!snapshot.history.some(item => item.text.includes("obsolete original history")),
    "summarized originals must not be rendered after compaction");
  assert.match(snapshot.history.find(item => item.role === "tool").text, /npm test[\s\S]*all tests passed/,
    "retained tool calls and output must survive UI process restart");
  const restoredEdit = snapshot.history.find(item => item.toolCallId === "tool-edit")?.text || "";
  assert.match(restoredEdit, /src\/example\.kt[\s\S]*-2 old[\s\S]*\+2 new[\s\S]*Successfully applied 1 edit/,
    "edit result details.diff must reach the existing expandable Android tool card");
  assert.match(restoredEdit, /11 final[\s\S]*Successfully applied 1 edit$/,
    "the restored edit card must retain the complete diff for manual expansion");
  assert.equal(snapshot.pendingUi[0]?.id, "tree-dialog", "an open /tree selector must survive UI process restart");
  assert.ok(
    snapshot.events.some(item => item.value?.widgetKey === "__android_loaded_extensions" && item.value?.widgetLines?.length === 2),
    "persistent extension widgets must survive Android UI reconnects",
  );
  assert.ok(
    snapshot.events.some(item => item.value?.widgetKey === "__android_loaded_resources" && item.value?.widgetLines?.includes("[Context]")),
    "categorized resource widgets must survive Android UI reconnects",
  );

  const closeTree = await fetch(`http://127.0.0.1:${port}/extension-ui`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ id: "tree-dialog", cancelled: true }),
  });
  assert.equal(closeTree.status, 200);

  const activePrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "__snapshot_active__" }),
  });
  assert.equal(activePrompt.status, 200);
  const activeSnapshot = await fetch(`http://127.0.0.1:${port}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.ok(
    activeSnapshot.events.some(item => item.value?.assistantMessageEvent?.delta === "live partial"),
    "an unfinished assistant message must survive UI process restart",
  );
  assert.ok(
    activeSnapshot.events.some(item => item.value?.type === "queue_update" && item.value?.steering?.[0] === "queued after restart"),
    "the current steering queue must survive UI process restart",
  );
  assert.ok(
    !activeSnapshot.events.some(item => item.value?.toolCallId === "already-durable"),
    "completed tool events already represented by durable history must not be replayed",
  );
  assert.ok(
    activeSnapshot.events.some(item => item.value?.type === "tool_execution_end" && item.value?.toolCallId === "not-yet-durable"),
    "a completed tool result not yet represented by the history response must still be replayed",
  );
  assert.ok(
    !activeSnapshot.events.some(item => item.value?.assistantMessageEvent?.delta === "completed duplicate"),
    "completed assistant deltas already represented by durable history must not be replayed",
  );

  const racePrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "__snapshot_race__" }),
  });
  assert.equal(racePrompt.status, 200);
  const raceSnapshot = await fetch(`http://127.0.0.1:${port}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.ok(
    raceSnapshot.events.some(item => item.value?.type === "message_end" && item.value?.message?.content?.[0]?.text === "race final"),
    "events emitted after the history response must not be lost when the agent settles during snapshot creation",
  );
  assert.ok(
    raceSnapshot.events.some(item => item.value?.type === "agent_settled"),
    "the recovered lifecycle must include the settled boundary",
  );

  const pressureCursor = await fetch(`http://127.0.0.1:${port}/events?after=0&wait=0`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json()).then(batch => batch.latest);
  const pressurePrompt = await fetch(`http://127.0.0.1:${port}/prompt`, {
    method: "POST",
    headers: startHeaders,
    body: JSON.stringify({ message: "__event_pressure__" }),
  });
  assert.equal(pressurePrompt.status, 200);
  const pressureBatch = await fetch(`http://127.0.0.1:${port}/events?after=${pressureCursor}&wait=0`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.equal(pressureBatch.gap, true, "byte-bounded event pruning must request a durable snapshot instead of exhausting memory");
  assert.ok(pressureBatch.events.length < 20, "the event cache must be bounded by payload bytes, not only event count");
  const pressureSnapshot = await fetch(`http://127.0.0.1:${port}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(response => response.json());
  assert.ok(
    pressureSnapshot.events.some(item => item.value?.type === "queue_update" && item.value?.steering?.[0] === "survives cache pressure"),
    "the latest steering queue must survive event-cache byte pruning",
  );

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
