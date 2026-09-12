import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const directory = mkdtempSync(join(tmpdir(), "pi-mobile-fork-"));
const sessionFile = join(directory, "session.jsonl");
const extension = fileURLToPath(new URL("../app/src/main/assets/pi-android-mobile.ts", import.meta.url));
const timestamp = new Date().toISOString();
const secretPrompt = "token=github_pat_TESTTOKENABCDEFGHIJKLMNOPQRSTUVWXYZ123456";
const usage = {
  input: 1,
  output: 1,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 2,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

const assistant = (text) => ({
  role: "assistant",
  content: [{ type: "text", text }],
  api: "test",
  provider: "test",
  model: "test",
  usage,
  stopReason: "stop",
  timestamp: Date.now(),
});

writeFileSync(sessionFile, [
  { type: "session", version: 3, id: "11111111-1111-4111-8111-111111111111", timestamp, cwd: directory },
  { type: "message", id: "aaaaaaaa", parentId: null, timestamp, message: { role: "user", content: "root prompt", timestamp: Date.now() } },
  { type: "message", id: "bbbbbbbb", parentId: "aaaaaaaa", timestamp, message: assistant("root answer") },
  { type: "message", id: "cccccccc", parentId: "bbbbbbbb", timestamp, message: { role: "user", content: secretPrompt, timestamp: Date.now() } },
  { type: "message", id: "dddddddd", parentId: "cccccccc", timestamp, message: assistant("historical answer") },
  { type: "message", id: "12121212", parentId: "bbbbbbbb", timestamp, message: { role: "user", content: "alternate branch prompt", timestamp: Date.now() } },
  { type: "message", id: "eeeeeeee", parentId: "dddddddd", timestamp, message: { role: "user", content: "retained prompt", timestamp: Date.now() } },
  { type: "message", id: "ffffffff", parentId: "eeeeeeee", timestamp, message: assistant("retained answer") },
  { type: "compaction", id: "13131313", parentId: "ffffffff", timestamp, summary: "summary", firstKeptEntryId: "eeeeeeee", tokensBefore: 50000 },
  { type: "message", id: "14141414", parentId: "13131313", timestamp, message: { role: "user", content: "post-compaction prompt", timestamp: Date.now() } },
].map(JSON.stringify).join("\n") + "\n");

const piArgs = ["--mode", "rpc", "--session", sessionFile, "-e", extension];
const child = process.env.PI_BIN
  ? spawn(process.execPath, [process.env.PI_BIN, ...piArgs], { stdio: ["pipe", "pipe", "inherit"] })
  : spawn("pi", piArgs, { stdio: ["pipe", "pipe", "inherit"] });

let buffer = "";
let editorText = "";
let switched = false;
let completed = false;
let replacementFile = "";

function send(value) {
  child.stdin.write(`${JSON.stringify(value)}\n`);
}

function fail(message) {
  throw new Error(message);
}

function handle(value) {
  if (value.type === "extension_ui_request" && value.method === "select" && value.title === "从消息创建 Fork") {
    const options = value.options || [];
    if (!options[0]?.includes("[当前上下文]") || !options[0]?.includes("post-compaction prompt")) {
      fail("fork selector must open with the newest active-context prompt");
    }
    if (!options.some((option) => option.includes("[已压缩]") && option.includes("cccccccc"))) {
      fail("compacted prompts must be clearly marked as archived");
    }
    if (!options.some((option) => option.includes("[其他分支]") && option.includes("alternate branch prompt"))) {
      fail("off-branch prompts must be clearly marked");
    }
    if (options.some((option) => option.includes("TESTTOKEN") || option.includes("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))) {
      fail("credential material leaked into the fork selector");
    }
    const archived = options.find((option) => option.includes("cccccccc"));
    if (!archived?.includes("[REDACTED]")) fail("credential preview was not redacted");
    send({ type: "extension_ui_response", id: value.id, value: archived });
  }

  if (value.type === "extension_ui_request" && value.method === "confirm" && value.title === "确认历史 Fork") {
    if (!value.message?.includes("不在当前模型上下文") || !value.message?.includes("独立 session")) {
      fail("historical fork warning is incomplete");
    }
    send({ type: "extension_ui_response", id: value.id, confirmed: true });
  }

  if (value.type === "extension_ui_request" && value.method === "set_editor_text") {
    editorText = value.text;
  }

  if (value.type === "extension_ui_request" && value.method === "notify" && value.message === "ANDROID_SESSION_SWITCHED") {
    switched = true;
    send({ id: "fork-state", type: "get_state" });
  }

  if (value.id === "fork-state") {
    const state = value.data || {};
    replacementFile = state.sessionFile || "";
    if (!replacementFile || replacementFile === sessionFile) fail("fork did not create and switch to a new session file");
    if (state.messageCount !== 2) fail(`fork restored the wrong checkpoint: ${state.messageCount} messages`);
    if (editorText !== secretPrompt) fail("selected user prompt was not restored into the replacement editor");
    if (!switched) fail("Android session-switch notification was not emitted");

    const header = JSON.parse(readFileSync(replacementFile, "utf8").split(/\r?\n/, 1)[0]);
    if (header.parentSession !== sessionFile) fail("forked session did not record its parent session");
    completed = true;
    child.kill();
  }
}

child.stdout.on("data", (chunk) => {
  buffer += chunk;
  while (true) {
    const newline = buffer.indexOf("\n");
    if (newline < 0) break;
    const raw = buffer.slice(0, newline);
    buffer = buffer.slice(newline + 1);
    if (raw) handle(JSON.parse(raw));
  }
});

child.on("spawn", () => send({ id: "fork", type: "prompt", message: "/fork" }));
child.on("exit", () => {
  rmSync(directory, { recursive: true, force: true });
  if (!completed) process.exitCode = 1;
  else console.log("Mobile /fork behavior test passed");
});
setTimeout(() => {
  if (!completed) {
    console.error("Mobile /fork behavior test timed out");
    child.kill();
  }
}, 20_000);
