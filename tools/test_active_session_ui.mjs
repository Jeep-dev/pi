import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const main = await readFile("app/src/main/java/com/piandroid/MainActivity.kt", "utf8");
const runtime = await readFile("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");
const markdown = await readFile("app/src/main/java/com/piandroid/MarkdownContent.kt", "utf8");

assert.ok(main.includes("key(activeSession.androidSessionId)"), "active Session must own the only PiScreen");
assert.ok(main.includes("session = activeSession"), "PiScreen must receive the selected Session record");
assert.ok(main.includes("autoStart = true"), "selected Session must activate its runtime");
assert.ok(!main.includes("sessions.forEach { record ->\\n                        key(record.androidSessionId)"), "inactive Sessions must not retain hidden PiScreen trees");
assert.ok(runtime.includes("val generation = ++conversationGeneration"), "activation must fence stale event batches");
assert.ok(runtime.includes("connectCurrent(true, generation)"), "activation must refresh from its own Bridge endpoint");
console.log("Active-session UI ownership guards passed");

assert.ok(main.includes("awaitPointerEvent(PointerEventPass.Final)"), "Session drawer must wait for horizontal child scroll surfaces");
assert.ok(main.includes("change.isConsumed"), "consumed child drags must not open the Session drawer");
assert.ok(markdown.includes("Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())"), "wide Markdown tables need a full-width horizontal viewport");

assert.ok(main.includes("stableBottomFrames"), "bottom follow must survive delayed Compose remeasurement");
assert.ok(main.includes("repeat(16)"), "bottom follow must wait across multiple layout frames");
assert.ok(main.includes("scrollToRealBottom { followOutput }"), "automatic scrolling must stop immediately after the user disables follow");
assert.ok(main.includes("abs(available.y) > abs(available.x)"), "horizontal table/code scrolling must not disable bottom follow");
