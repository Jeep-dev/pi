from pathlib import Path

main = Path("app/src/main/java/com/piandroid/MainActivity.kt")
text = main.read_text()
old = r'''                    // Each retained screen has one owner-specific Runtime. Only
                    // the active Android Session is allowed to present dialogs.
                    sessions.forEach { record ->
                        key(record.androidSessionId) {
                            val active = record.androidSessionId == activeSession.androidSessionId
                            val runtime = if (active) {
                                runtimeManager.activate(record)
                            } else {
                                runtimeManager.runtime(record)
                            }
                            PiScreen(
                                runtime = runtime,
                                session = record,
                                sessions = sessions,
                                activeAndroidSessionId = activeAndroidSessionId,
                                autoStart = active,
                                hostModifier = if (active) Modifier.fillMaxSize() else Modifier.size(0.dp),
                                themeMode = themeMode,
                                onTheme = { selected ->
                                    themeKey = selected.storageKey
                                    context.getSharedPreferences("pi_ui", Context.MODE_PRIVATE)
                                        .edit().putString("theme", selected.storageKey).apply()
                                },
                                onSelectSession = ::selectSession,
                                onNewSession = {
                                    createSessionName = ""
                                    createSessionCwd = activeSession.cwd
                                    createSessionStartupArguments = ""
                                    createSessionOpen = true
                                },
                                onSessionUpdate = ::updateSession,
                                onCanBindConversation = ::canBindConversation,
                                onManageSession = ::requestSessionManagement
                            )
                        }
                    }
'''
new = r'''                    // Render exactly one chat UI. Background Runtime objects remain
                    // Activity-owned, but an inactive Session must never keep a hidden
                    // Compose chat tree that can leak/replay another Session's UI state.
                    key(activeSession.androidSessionId) {
                        val runtime = runtimeManager.activate(activeSession)
                        PiScreen(
                            runtime = runtime,
                            session = activeSession,
                            sessions = sessions,
                            activeAndroidSessionId = activeAndroidSessionId,
                            autoStart = true,
                            hostModifier = Modifier.fillMaxSize(),
                            themeMode = themeMode,
                            onTheme = { selected ->
                                themeKey = selected.storageKey
                                context.getSharedPreferences("pi_ui", Context.MODE_PRIVATE)
                                    .edit().putString("theme", selected.storageKey).apply()
                            },
                            onSelectSession = ::selectSession,
                            onNewSession = {
                                createSessionName = ""
                                createSessionCwd = activeSession.cwd
                                createSessionStartupArguments = ""
                                createSessionOpen = true
                            },
                            onSessionUpdate = ::updateSession,
                            onCanBindConversation = ::canBindConversation,
                            onManageSession = ::requestSessionManagement
                        )
                    }
'''
if old not in text:
    raise SystemExit("MainActivity active-screen block not found")
main.write_text(text.replace(old, new, 1))

runtime = Path("app/src/main/java/com/piandroid/PiSessionRuntime.kt")
text = runtime.read_text()
old = r'''            if (connected) {
                val state = lastState
                val snapshot = lastSnapshot
                if (state != null && snapshot != null) {
                    updatesMutable.tryEmit(PiRuntimeUpdate.Ready(PiRuntimeReady(state, snapshot)))
                }
                return
            }
'''
new = r'''            if (connected) {
                val state = lastState
                val snapshot = lastSnapshot
                if (autoStart) {
                    // A newly visible PiScreen starts with empty Compose state. Do not
                    // replay a cached snapshot from the last time this Session was visible:
                    // refresh from this Runtime's own Bridge endpoint and fence any event
                    // batch that was in flight before activation.
                    val generation = ++conversationGeneration
                    scope.launch {
                        val refreshed = runCatching { connectCurrent(true, generation) }
                        val ready = refreshed.getOrNull()
                        if (ready != null) {
                            if (!publishReady(ready, generation)) {
                                updatesMutable.emit(
                                    PiRuntimeUpdate.Failed(
                                        IllegalStateException("Pi conversation is already owned by another Android Session")
                                    )
                                )
                            }
                        } else {
                            val error = refreshed.exceptionOrNull()
                            if (error != null && isConversationGeneration(generation)) {
                                updatesMutable.emit(PiRuntimeUpdate.Reconnecting(error))
                            }
                        }
                    }
                } else if (state != null && snapshot != null) {
                    updatesMutable.tryEmit(PiRuntimeUpdate.Ready(PiRuntimeReady(state, snapshot)))
                }
                return
            }
'''
if old not in text:
    raise SystemExit("PiSessionRuntime connected branch not found")
runtime.write_text(text.replace(old, new, 1))

manifest = Path("app/src/main/AndroidManifest.xml")
text = manifest.read_text()
provider = r'''        <provider
            android:name=".SessionOwnershipRepairProvider"
            android:authorities="${applicationId}.session-ownership-repair"
            android:exported="false"
            android:initOrder="100" />
'''
if provider not in text:
    raise SystemExit("repair provider manifest block not found")
manifest.write_text(text.replace(provider, "", 1))

gradle = Path("app/build.gradle.kts")
text = gradle.read_text()
if 'versionCode = 110' not in text or 'versionName = "5.19.11"' not in text:
    raise SystemExit("unexpected Android version")
gradle.write_text(
    text.replace("versionCode = 110", "versionCode = 111", 1)
        .replace('versionName = "5.19.11"', 'versionName = "5.19.12"', 1)
)

workflow = Path(".github/workflows/android.yml")
text = workflow.read_text()
if "pi-android-v5.19.11-apks" not in text:
    raise SystemExit("unexpected artifact version")
text = text.replace("pi-android-v5.19.11-apks", "pi-android-v5.19.12-apks")
marker = '      - "tools/test_multi_session_bridge.mjs"\n'
if "tools/test_active_session_ui.mjs" not in text:
    text = text.replace(marker, marker + '      - "tools/test_active_session_ui.mjs"\n')
    text = text.replace(
        "          node tools/test_multi_session_bridge.mjs\n",
        "          node tools/test_multi_session_bridge.mjs\n          node tools/test_active_session_ui.mjs\n",
        1,
    )
workflow.write_text(text)

Path("tools/test_active_session_ui.mjs").write_text(r'''import assert from "node:assert/strict";
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
''')
