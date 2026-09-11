import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
    id: "dddddddd",
    parentId: "bbbbbbbb",
    timestamp,
    message: {
      role: "assistant",
      content: [{ type: "toolCall", id: "tool-call", name: "bash", arguments: { command: "echo branch" } }],
      api: "test",
      provider: "test",
      model: "test",
      usage,
      stopReason: "toolUse",
      timestamp: Date.now(),
    },
  },
  {
    type: "message",
    id: "eeeeeeee",
    parentId: "dddddddd",
    timestamp,
    message: { role: "toolResult", toolCallId: "tool-call", toolName: "bash", content: [{ type: "text", text: "branch output" }], isError: false, timestamp: Date.now() },
  },
  {
    type: "message",
    id: "cccccccc",
    parentId: "bbbbbbbb",
    timestamp,
    message: { role: "user", content: "second prompt", timestamp: Date.now() },
  },
].map(JSON.stringify).join("\n") + "\n");

const piArgs = [
  "--mode", "rpc",
  "--session", sessionFile,
  "-e", extension,
];
const child = process.env.PI_BIN
  ? spawn(process.execPath, [process.env.PI_BIN, ...piArgs], { stdio: ["pipe", "pipe", "inherit"] })
  : spawn("pi", piArgs, { stdio: ["pipe", "pipe", "inherit"] });

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
      value.options.some((option) => option.includes("second prompt") && option.includes("◆")) &&
      value.options.some((option) => option.includes("●")) &&
      value.options.length === 2 &&
      !value.options.some((option) => option.includes("first answer")) &&
      !value.options.some((option) => option.includes("assistant: (tool call)")) &&
      !value.options.some((option) => option.includes("[bash]"));
    const target = value.options.find((option) => option.includes("aaaaaaaa"));
    send({ type: "extension_ui_response", id: value.id, value: target });
  }
  if (value.type === "extension_ui_request" && value.method === "select" && value.title === "Summarize branch?") {
    throw new Error("Tree navigation must branch immediately without a second summary dialog");
  }
  if (value.type === "extension_ui_request" && value.method === "set_editor_text") editorText = value.text;
  if (value.type === "extension_ui_request" && value.method === "notify" && value.message === "ANDROID_SESSION_SWITCHED") {
    switched = true;
  }
  if (value.id === "navigate") send({ id: "tree", type: "get_tree" });
  if (value.id === "tree") {
    const nodes = [];
    const visit = (items) => items.forEach((item) => { nodes.push(item); visit(item.children || []); });
    visit(value.data.tree || []);
    const leaf = nodes.find((node) => node.entry.id === value.data.leafId)?.entry;
    if (leaf?.type !== "custom" || leaf.customType !== "__android_tree_edit__" || leaf.parentId !== null) {
      throw new Error("Selecting the root user message was not durably persisted before that message");
    }
    if (leaf.data?.editorText !== "root prompt" || leaf.data?.targetId !== "aaaaaaaa") {
      throw new Error("The editable root prompt was not persisted for process restart recovery");
    }
    const persisted = JSON.parse(readFileSync(sessionFile, "utf8").trim().split("\n").at(-1));
    if (persisted.customType !== "__android_tree_edit__" || persisted.data?.editorText !== "root prompt") {
      throw new Error("Tree edit marker was not written to the session file");
    }
    if (editorText !== "root prompt") throw new Error(`Editor text was not restored: ${editorText}`);
    if (!switched) throw new Error("Session switch notification was not emitted");
    if (!treeWasComplete) throw new Error("Tree selector omitted conversation entries or active-path state");
    send({ id: "root-messages", type: "get_messages" });
  }
  if (value.id === "root-messages") {
    if ((value.data.messages || []).length !== 0) throw new Error("Selected user message leaked into context before editing");
    send({ id: "branch", type: "prompt", message: "/tree cccccccc" });
  }
  if (value.id === "branch") send({ id: "branch-tree", type: "get_tree" });
  if (value.id === "branch-tree") {
    const nodes = [];
    const visit = (items) => items.forEach((item) => { nodes.push(item); visit(item.children || []); });
    visit(value.data.tree || []);
    const leaf = nodes.find((node) => node.entry.id === value.data.leafId)?.entry;
    if (leaf?.type !== "custom" || leaf.customType !== "__android_tree_edit__" || leaf.data?.targetId !== "cccccccc") {
      throw new Error("Selected user branch was not persisted at the chosen conversation point");
    }
    send({ id: "branch-messages", type: "get_messages" });
  }
  if (value.id === "branch-messages") {
    const messages = value.data.messages || [];
    if (messages.length !== 2 || !JSON.stringify(messages[1]).includes("first answer")) {
      throw new Error("Messages after the selected tree point leaked into model context");
    }
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
