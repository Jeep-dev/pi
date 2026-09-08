from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")
pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")
ext = Path("app/src/main/assets/pi-android-mobile.ts")

# v4.4 isolates Pi startup from all Android extensions. Commands that already use
# direct RPC remain available; extension-backed commands are intentionally disabled
# in this diagnostic build until the base RPC process is proven stable again.
replace_once(
    main,
    'mutableStateOf("pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts")',
    'mutableStateOf("pi --mode rpc")',
)

# Avoid ever reconnecting to an older bridge process left behind on the previous port.
replace_once(pi_bridge, "private val port = 17643", "private val port = 17644")
replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.3"',
    'private val expectedBridgeVersion = "2026-09-09.4"',
)

replace_once(bridge, "PI_ANDROID_PORT || 17643", "PI_ANDROID_PORT || 17644")
replace_once(bridge, 'const bridgeVersion = "2026-09-09.3";', 'const bridgeVersion = "2026-09-09.4";')
replace_once(
    bridge,
    'let launchCommand = "pi --mode rpc -e ~/.pi/android/pi-android-mobile.ts";',
    'let launchCommand = "pi --mode rpc";',
)

# Preserve enough process diagnostics to make another startup failure actionable.
replace_once(
    bridge,
    'let lastStderr = "";\nconst events = [];',
    'let lastStderr = "";\nlet lastStdoutTail = "";\nlet lastExit = null;\nconst events = [];',
)
bridge_text = bridge.read_text(encoding="utf-8")
bridge_text = bridge_text.replace(
    'catch { addEvent({ type: "raw", line }); }',
    'catch { lastStdoutTail = (lastStdoutTail + "\\n" + line).slice(-8000); addEvent({ type: "raw", line }); }',
)
bridge.write_text(bridge_text, encoding="utf-8")
replace_once(
    bridge,
    'lastStderr = "";\n\n  try {',
    'lastStderr = "";\n  lastStdoutTail = "";\n  lastExit = null;\n\n  try {',
)
replace_once(
    bridge,
    'child.on("exit", (code, signal) => {\n    addEvent({ type: "process_exit", code, signal, stderr: lastStderr });',
    'child.on("exit", (code, signal) => {\n    lastExit = { code, signal };\n    addEvent({ type: "process_exit", code, signal, stderr: lastStderr, stdout: lastStdoutTail });',
)
replace_once(
    bridge,
    'if (!child || child.exitCode != null) throw new Error(lastStderr.trim() || "Pi failed to start");',
    'if (!child || child.exitCode != null) {\n    const exitText = lastExit ? `exit=${lastExit.code ?? "?"} signal=${lastExit.signal ?? "-"}` : "exit=unknown";\n    const details = [lastStderr.trim(), lastStdoutTail.trim()].filter(Boolean).join("\\n--- stdout ---\\n");\n    throw new Error(`Pi failed to start (${exitText})${details ? `\\n${details}` : ""}`);\n  }',
)
replace_once(
    bridge,
    'lastStderr,\n        launcher: {',
    'lastStderr,\n        lastStdoutTail,\n        lastExit,\n        launcher: {',
)

# The extension is not loaded in v4.4; make the packaged file inert as a second guard.
ext.write_text('export default function () {}\n', encoding="utf-8")

print("Applied PiTouch v4.4 startup isolation patch")
