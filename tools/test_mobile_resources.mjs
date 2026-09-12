import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const directory = mkdtempSync(join(tmpdir(), "pi-mobile-resources-"));
const extension = fileURLToPath(new URL("../app/src/main/assets/pi-android-mobile.ts", import.meta.url));
writeFileSync(join(directory, "AGENTS.md"), "Android resource test context\n");
writeFileSync(join(directory, "demo-skill.md"), "---\nname: demo-skill\ndescription: Demo skill\n---\n# Demo skill\n");
writeFileSync(join(directory, "review.md"), "---\ndescription: Review prompt\n---\nReview this change.\n");

const piBin = process.env.PI_BIN;
const child = piBin
  ? spawn(process.execPath, [piBin, "--mode", "rpc", "--no-session", "--skill", join(directory, "demo-skill.md"), "--prompt-template", join(directory, "review.md"), "-e", extension], { cwd: directory, stdio: ["pipe", "pipe", "inherit"], env: { ...process.env, PI_OFFLINE: "1" } })
  : spawn("pi", ["--mode", "rpc", "--no-session", "--skill", join(directory, "demo-skill.md"), "--prompt-template", join(directory, "review.md"), "-e", extension], { cwd: directory, stdio: ["pipe", "pipe", "inherit"], env: { ...process.env, PI_OFFLINE: "1" } });

let buffer = "";
let initialWidget;
let refreshedWidget;
let commandResponse = false;
let reloadRequested = false;
let reloadWidget = false;
let reloadNotified = false;
let reloadResponse = false;
let failed = false;
let done;
const result = new Promise((resolve, reject) => { done = { resolve, reject }; });
const timeout = setTimeout(() => done.reject(new Error("resource widget test timed out")), 15_000);

function finishIfReloaded() {
  if (reloadRequested && reloadWidget && reloadNotified && reloadResponse) done.resolve();
}

function send(value) {
  child.stdin.write(`${JSON.stringify(value)}\n`);
}

function section(lines, title) {
  const index = lines.indexOf(`[${title}]`);
  return index < 0 ? [] : (lines[index + 1] || "").trim().split(",").map(item => item.trim()).filter(Boolean);
}

function handle(value) {
  if (value.type === "extension_ui_request" && value.method === "setWidget" && value.widgetKey === "__android_loaded_resources") {
    if (!initialWidget) {
      initialWidget = value.widgetLines;
      assert.deepEqual(section(initialWidget, "Skills"), ["demo-skill"]);
      assert.deepEqual(section(initialWidget, "Prompts"), ["/review"]);
      assert.ok(section(initialWidget, "Extensions").includes("pi-android-mobile.ts"));
      assert.ok(!section(initialWidget, "Extensions").includes("llama.cpp"));
      assert.ok(!section(initialWidget, "Extensions").includes("review.md"));
      assert.ok(!section(initialWidget, "Extensions").includes("demo-skill.md"));
      send({ id: "resources", type: "prompt", message: "/__android_loaded_resources" });
      return;
    }
    if (!refreshedWidget && value.widgetLines.some(line => line === "[Context]")) {
      refreshedWidget = value.widgetLines;
      assert.ok(section(refreshedWidget, "Context").includes("AGENTS.md"));
      assert.deepEqual(section(refreshedWidget, "Skills"), ["demo-skill"]);
      assert.deepEqual(section(refreshedWidget, "Prompts"), ["/review"]);
    } else if (reloadRequested) {
      reloadWidget = true;
      finishIfReloaded();
    }
  }
  if (value.type === "extension_ui_request" && value.method === "notify" && value.message === "资源已重新加载") {
    reloadNotified = true;
    finishIfReloaded();
  }
  if (value.id === "resources" && value.type === "response") {
    commandResponse = true;
    if (refreshedWidget && !reloadRequested) {
      reloadRequested = true;
      send({ id: "reload", type: "prompt", message: "/reload" });
    }
  }
  if (value.id === "reload" && value.type === "response") {
    reloadResponse = true;
    finishIfReloaded();
  }
}

child.stdout.on("data", chunk => {
  buffer += chunk;
  while (true) {
    const newline = buffer.indexOf("\n");
    if (newline < 0) break;
    const raw = buffer.slice(0, newline);
    buffer = buffer.slice(newline + 1);
    if (!raw) continue;
    try { handle(JSON.parse(raw)); }
    catch (error) { failed = true; done.reject(error); }
  }
});
child.on("error", error => { failed = true; done.reject(error); });
child.on("exit", code => {
  if (!failed && code !== null && code !== 0) done.reject(new Error(`Pi exited with code ${code}`));
});

try {
  await result;
  assert.ok(initialWidget, "initial categorized resource widget was not emitted");
  assert.ok(refreshedWidget, "post-discovery resource widget was not emitted");
  assert.ok(commandResponse, "resource refresh command did not complete");
  assert.ok(reloadWidget && reloadNotified && reloadResponse, "resource categories were not republished after reload");
  console.log("Mobile resource categories test passed");
} finally {
  clearTimeout(timeout);
  child.kill();
  rmSync(directory, { recursive: true, force: true });
}
