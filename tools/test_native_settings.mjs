import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";

// Native /settings parity: the RPC commands behind the Android settings rows must
// exist in the Pi version CI installs and round-trip through get_state.
const directory = mkdtempSync(join(tmpdir(), "pi-native-settings-"));
const piBin = process.env.PI_BIN;
const args = ["--mode", "rpc", "--no-session"];
const child = piBin
  ? spawn(process.execPath, [piBin, ...args], { cwd: directory, stdio: ["pipe", "pipe", "inherit"], env: { ...process.env, PI_OFFLINE: "1" } })
  : spawn("pi", args, { cwd: directory, stdio: ["pipe", "pipe", "inherit"], env: { ...process.env, PI_OFFLINE: "1" } });

const waiting = new Map();
let buffer = "";
child.stdout.setEncoding("utf8");
child.stdout.on("data", chunk => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    const value = JSON.parse(line);
    if (value.type === "response" && waiting.has(value.id)) {
      waiting.get(value.id)(value);
      waiting.delete(value.id);
    }
  }
});

let counter = 0;
function rpc(command) {
  const id = `t${++counter}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout: ${command.type}`)), 15_000);
    waiting.set(id, value => { clearTimeout(timer); resolve(value); });
    child.stdin.write(`${JSON.stringify({ ...command, id })}\n`);
  });
}

try {
  const levels = await rpc({ type: "get_available_thinking_levels" });
  assert.equal(levels.success, true, JSON.stringify(levels));
  assert.ok(Array.isArray(levels.data?.levels) && levels.data.levels.includes("off"), "thinking levels must include off");

  for (const [type, field] of [["set_steering_mode", "steeringMode"], ["set_follow_up_mode", "followUpMode"]]) {
    for (const mode of ["all", "one-at-a-time"]) {
      const response = await rpc({ type, mode });
      assert.equal(response.success, true, JSON.stringify(response));
      const state = await rpc({ type: "get_state" });
      assert.equal(state.data?.[field], mode, `${field} did not round-trip`);
    }
  }

  const retry = await rpc({ type: "set_auto_retry", enabled: false });
  assert.equal(retry.success, true, JSON.stringify(retry));
  console.log("Native settings RPC tests passed");
} finally {
  child.kill();
  rmSync(directory, { recursive: true, force: true });
}
