import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const main = await readFile("app/src/main/java/com/piandroid/MainActivity.kt", "utf8");
const runtime = await readFile("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");

assert.ok(main.includes("key(activeSession.androidSessionId)"), "active Session must own the only PiScreen");
assert.ok(main.includes("session = activeSession"), "PiScreen must receive the selected Session record");
assert.ok(main.includes("autoStart = true"), "selected Session must activate its runtime");
assert.ok(!main.includes("sessions.forEach { record ->\\n                        key(record.androidSessionId)"), "inactive Sessions must not retain hidden PiScreen trees");
assert.ok(runtime.includes("val generation = ++conversationGeneration"), "activation must fence stale event batches");
assert.ok(runtime.includes("connectCurrent(true, generation)"), "activation must refresh from its own Bridge endpoint");
console.log("Active-session UI ownership guards passed");
