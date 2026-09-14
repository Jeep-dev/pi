import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const bridge = readFileSync("app/src/main/java/com/piandroid/PiBridge.kt", "utf8");
const runtime = readFileSync("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");
const main = readFileSync("app/src/main/java/com/piandroid/MainActivity.kt", "utf8");

const start = bridge.indexOf("suspend fun shutdownRuntime()");
const end = bridge.indexOf("suspend fun installAndStartBridge", start);
assert.ok(start >= 0 && end > start, "shutdownRuntime block must exist");
const closeBlock = bridge.slice(start, end);
assert.match(closeBlock, /request\("\/shutdown"/);
assert.doesNotMatch(closeBlock, /rm\s+-/);
assert.doesNotMatch(closeBlock, /unlink/);
assert.doesNotMatch(closeBlock, /forgetEndpointToken/);
assert.doesNotMatch(closeBlock, /runtimePreferences\(\)\.edit/);
assert.match(runtime, /bridge\.shutdownRuntime\(\)/);
assert.doesNotMatch(runtime, /shutdownAndCleanup/);
assert.match(main, /不会删除任何 Pi 会话历史或项目文件/);
console.log("session close preserves Pi history: ok");
