import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const directory = mkdtempSync(join(tmpdir(), "pi-mobile-tree-"));
const sessionFile = join(directory, "session.jsonl");
const extension = fileURLToPath(new URL("../app/src/main/assets/pi-android-mobile.ts", import.meta.url));
const timestamp = new Date().toISOString();
const usage = {
  input: 1,
  output: 1,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 2,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

writeFileSync(sessionFile, [
  { type: "session", version: 3, id: "11111111-1111-4111-8111-111111111111", timestamp, cwd: directory },
  {
    type: "message",
    id: "aaaaaaaa",
    parentId: null,
    timestamp,
    message: { role: "user", content: "root prompt", timestamp: Date.now() },
  },
  {
    type: "message",
    id: "bbbbbbbb",
    parentId: "aaaaaaaa",
    timestamp,
    message: {
      role: "assistant",
      content: [{ type: "text", text: "first answer" }],
      api: "test",
      provider: "test",
      model: "test",
      usage,
      stopReason: "stop",
      timestamp: Date.now(),
    },
  },
  {
    type: "message",
    id: "cccccccc",
    parentId: "bbbbbbbb",
    timestamp,
    message: { role: "user", content: "second prompt", timestamp: Date.now() },
  },
].map(JSON.stringify).join("\n") + "\n");

const child = spawn(process.env.PI_BIN || "pi", [
  "--mode", "rpc",
  "--session", sessionFile,
  "-e", extension,
], { stdio: ["pipe", "pipe", "inherit"] });

let buffer = "";
let editorText = "";
let switched = false;
let treeWasComplete = false;
let completed = false;

function send(value) {
  child.stdin.write(`${JSON.stringify(value)}\n`);
}

function handle(value) {
  if (value.type === "extension_ui_request" && value.method === "select" && value.title === "Session Tree") {
    treeWasComplete =
      value.options.some((option) => option.includes("root prompt")) &&
      value.options.some((option) => option.includes("first answer")) &&
      value.options.some((option) => option.includes("second prompt")) &&
      value.options.some((option) => option.includes("●"));
    const target = value.options.find((option) => option.includes("aaaaaaaa"));
    send({ type: "extension_ui_response", id: value.id, value: target });
  }
  if (value.type === "extension_ui_request" && value.method === "select" && value.title === "Summarize branch?") {
    send({ type: "extension_ui_response", id: value.id, value: "No summary" });
  }
  if (value.type === "extension_ui_request" && value.method === "set_editor_text") editorText = value.text;
  if (value.type === "extension_ui_request" && value.method === "notify" && value.message === "ANDROID_SESSION_SWITCHED") {
    switched = true;
  }
  if (value.id === "navigate") send({ id: "tree", type: "get_tree" });
  if (value.id === "tree") {
    if (value.data.leafId !== null) throw new Error("Selecting the root user message did not reset the leaf");
    if (editorText !== "root prompt") throw new Error(`Editor text was not restored: ${editorText}`);
    if (!switched) throw new Error("Session switch notification was not emitted");
    if (!treeWasComplete) throw new Error("Tree selector omitted conversation entries or active-path state");
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

child.on("spawn", () => send({ id: "navigate", type: "prompt", message: "/tree" }));
child.on("exit", () => {
  rmSync(directory, { recursive: true, force: true });
  if (!completed) process.exitCode = 1;
  else console.log("Mobile /tree navigation test passed");
});
setTimeout(() => {
  if (!completed) {
    console.error("Mobile /tree navigation test timed out");
    child.kill();
  }
}, 15_000);
