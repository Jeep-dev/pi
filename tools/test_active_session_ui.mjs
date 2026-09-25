import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const main = await readFile("app/src/main/java/com/piandroid/MainActivity.kt", "utf8");
const runtime = await readFile("app/src/main/java/com/piandroid/PiSessionRuntime.kt", "utf8");
const markdown = await readFile("app/src/main/java/com/piandroid/MarkdownContent.kt", "utf8");
const bridge = await readFile("app/src/main/assets/pi-android-bridge.mjs", "utf8");
const extras = await readFile("app/src/main/java/com/piandroid/PiBridgeExtras.kt", "utf8");

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
assert.ok(main.includes("val verticalMovement = abs(consumed.y) > abs(consumed.x)"), "only real vertical list movement may change bottom follow");
assert.ok(main.includes("val userActuallyMoved = source == NestedScrollSource.UserInput && verticalMovement"), "bottom follow must ignore non-user/programmatic scrolling");
assert.ok(main.includes("Observe measured LazyColumn geometry as well as message data"), "late tool/Markdown layout growth must retrigger bottom follow");

assert.ok(main.includes("val collapseInfo = when"), "tool footer must derive Pi-style hidden-line metadata");
assert.ok(main.includes("contentAlignment = Alignment.CenterStart"), "tool footer must show hidden-line metadata on the left");
assert.ok(main.includes("contentAlignment = Alignment.CenterEnd"), "tool footer must keep execution duration on the right");

assert.ok(main.includes("toolDurationMs = message.toolDurationMs"), "restored tool cards must retain durable execution duration");
assert.ok(main.includes("toolName = message.toolName"), "restored tool cards must retain their real tool name");
assert.ok(main.includes("line.toolDurationMs >= 0L"), "tool renderer must prefer durable duration after history restore");
assert.ok(extras.includes("val toolDurationMs: Long = -1L"), "history DTO must carry tool duration metadata");
assert.ok(bridge.includes("tool-history-metadata-v1"), "bridge must advertise durable tool-history metadata");
assert.ok(bridge.includes("row.toolDurationMs = startedAtMs > 0"), "durable history must derive tool duration from persisted session timestamps");

// Reconnect must never kill Pi: the Bridge is replaced only when it is gone or
// from an older APK, and the event loop rides the push stream.
const relaunches = runtime.split("installAndStartBridge()").length - 1;
assert.equal(relaunches, 1, "Bridge relaunch must have exactly one guarded call site");
assert.ok(/if \(!bridgeUsable\) \{[\s\S]*?installAndStartBridge\(\)/.test(runtime), "Bridge relaunch must be guarded by bridgeUsable");
assert.ok(runtime.includes("bridge.stream("), "event loop must use the push stream");
assert.ok(!runtime.includes("bridge.events("), "event loop must not long-poll /events");
assert.ok(runtime.includes("patientHealth()"), "attach must wait for a thawing Bridge before declaring it dead");
assert.ok(bridge.includes('url.pathname === "/stream"'), "bridge must serve the push stream");
console.log("Reconnect-without-restart guards passed");
