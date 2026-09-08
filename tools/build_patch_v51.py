from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:260]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")
manifest = Path("app/src/main/AndroidManifest.xml")

# v4.9/v5.0 still had a subtle lifecycle bug: the shell command ended with
# `... nohup node ... & echo $! > bridge.pid`. Because the background operator
# applied to the surrounding AND-list, bridge.pid could point at a transient
# shell rather than the actual Node bridge. Killing that PID on the next Connect
# could leave the old bridge alive on 17646, so a new bridge could never bind.
# The app would then either hit the stale bridge (and SIGTERM its Pi -> exit 143)
# or wait forever/return to Disconnected.
#
# Replace this with deterministic cleanup of every old bridge process and its
# direct Pi child, then background ONLY the Node bridge and persist its real PID.
old_launch = '''                if [ -f ~/.pi/android/bridge.pid ]; then kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true; fi &&\n                sleep 1 &&\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs > ~/.pi/android/bridge.log 2>&1 &\n                echo \\$! > ~/.pi/android/bridge.pid\n'''
new_launch = '''                if command -v pgrep >/dev/null 2>&1; then\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -TERM -P "\\$p" 2>/dev/null || true\n                        kill -TERM "\\$p" 2>/dev/null || true\n                    done\n                    sleep 0.35\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -KILL -P "\\$p" 2>/dev/null || true\n                        kill -KILL "\\$p" 2>/dev/null || true\n                    done\n                elif [ -f ~/.pi/android/bridge.pid ]; then\n                    kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true\n                    sleep 0.35\n                fi\n                rm -f ~/.pi/android/bridge.pid\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs </dev/null > ~/.pi/android/bridge.log 2>&1 &\n                bridge_pid=\\$!\n                echo "\\$bridge_pid" > ~/.pi/android/bridge.pid\n'''
replace_once(pi_bridge, old_launch, new_launch)

# Give this lifecycle protocol a new bridge identity so an old v4.9/v5.0 bridge
# can never satisfy the health handshake.
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

# Make the bridge explicitly own its Pi child. A future bridge replacement will
# terminate Pi first, close the HTTP server, and only then exit, preventing
# orphaned RPC processes and exit=143 races.
needle = '''const server = http.createServer(async (req, res) => {\n'''
insert = '''let shuttingDown = false;\n\nfunction shutdownBridge(signal) {\n  if (shuttingDown) return;\n  shuttingDown = true;\n  try { stopPi(); } catch {}\n  const timer = setTimeout(() => process.exit(0), 500);\n  timer.unref?.();\n  try {\n    server.close(() => process.exit(0));\n  } catch {\n    process.exit(0);\n  }\n}\n\nprocess.on("SIGTERM", () => shutdownBridge("SIGTERM"));\nprocess.on("SIGINT", () => shutdownBridge("SIGINT"));\n\nconst server = http.createServer(async (req, res) => {\n'''
replace_once(bridge, needle, insert)

# Even if Android/Termux sends a duplicate launcher intent, do not recreate the
# Compose Activity and wipe the live connection state. This is a second guard in
# addition to removing the PendingIntent callback in v5.0.
replace_once(
    manifest,
    '<activity android:name=".MainActivity" android:exported="true">',
    '<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask" android:alwaysRetainTaskState="true">',
)

print("Applied PiTouch v5.1 deterministic bridge lifecycle fix")
