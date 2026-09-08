from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:260]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")
manifest = Path("app/src/main/AndroidManifest.xml")

old_launch = '''                if [ -f ~/.pi/android/bridge.pid ]; then kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true; fi &&\n                sleep 1 &&\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs > ~/.pi/android/bridge.log 2>&1 &\n                echo \\$! > ~/.pi/android/bridge.pid\n'''
new_launch = '''                if command -v pgrep >/dev/null 2>&1; then\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -TERM -P "${'$'}p" 2>/dev/null || true\n                        kill -TERM "${'$'}p" 2>/dev/null || true\n                    done\n                    sleep 0.35\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -KILL -P "${'$'}p" 2>/dev/null || true\n                        kill -KILL "${'$'}p" 2>/dev/null || true\n                    done\n                elif [ -f ~/.pi/android/bridge.pid ]; then\n                    kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true\n                    sleep 0.35\n                fi\n                rm -f ~/.pi/android/bridge.pid\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs </dev/null > ~/.pi/android/bridge.log 2>&1 &\n                bridge_pid=\\$!\n                echo "${'$'}bridge_pid" > ~/.pi/android/bridge.pid\n'''
replace_once(pi_bridge, old_launch, new_launch)

replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.7"',
    'private val expectedBridgeVersion = "2026-09-09.8"',
)
replace_once(
    bridge,
    'const bridgeVersion = "2026-09-09.7";',
    'const bridgeVersion = "2026-09-09.8";',
)

needle = '''const server = http.createServer(async (req, res) => {\n'''
insert = '''let shuttingDown = false;\n\nfunction shutdownBridge(signal) {\n  if (shuttingDown) return;\n  shuttingDown = true;\n  try { stopPi(); } catch {}\n  const timer = setTimeout(() => process.exit(0), 500);\n  timer.unref?.();\n  try {\n    server.close(() => process.exit(0));\n  } catch {\n    process.exit(0);\n  }\n}\n\nprocess.on("SIGTERM", () => shutdownBridge("SIGTERM"));\nprocess.on("SIGINT", () => shutdownBridge("SIGINT"));\n\nconst server = http.createServer(async (req, res) => {\n'''
replace_once(bridge, needle, insert)

replace_once(
    manifest,
    '<activity android:name=".MainActivity" android:exported="true">',
    '<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask" android:alwaysRetainTaskState="true">',
)

print("Applied PiTouch v5.1 deterministic bridge lifecycle fix")
