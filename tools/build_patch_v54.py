from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:260]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
extras = Path("app/src/main/java/com/piandroid/PiBridgeExtras.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")

# Root cause of v5.2/v5.3: v5.1 replaced the old simple launcher with a multi-line
# pgrep/for-loop script, while PiBridge flattens the raw string with
# `.replace("\\n", " ")`. That removed shell command separators (`done sleep`,
# `fi rm`, etc.), so bash never reached the Node launch at all. In addition,
# pgrep -f could match the launcher shell itself because its command line contains
# `bridge.mjs` later in the script.
#
# Do not manage bridge PIDs from our own shell anymore. RUN_COMMAND_BACKGROUND is
# already a background runner, so make bash `exec` the bridge directly. This has
# no nohup/PID/pgrep race and no shell-loop syntax to flatten.
old_launch = '''                if command -v pgrep >/dev/null 2>&1; then\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -TERM -P "${'$'}p" 2>/dev/null || true\n                        kill -TERM "${'$'}p" 2>/dev/null || true\n                    done\n                    sleep 0.35\n                    for p in \\$(pgrep -f '[b]ridge\\.mjs' 2>/dev/null || true); do\n                        pkill -KILL -P "${'$'}p" 2>/dev/null || true\n                        kill -KILL "${'$'}p" 2>/dev/null || true\n                    done\n                elif [ -f ~/.pi/android/bridge.pid ]; then\n                    kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true\n                    sleep 0.35\n                fi\n                rm -f ~/.pi/android/bridge.pid\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs </dev/null > ~/.pi/android/bridge.log 2>&1 &\n                bridge_pid=\\$!\n                echo "${'$'}bridge_pid" > ~/.pi/android/bridge.pid\n'''
new_launch = '''                export PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port &&\n                exec /data/data/com.termux/files/usr/bin/node ~/.pi/android/bridge.mjs >> ~/.pi/android/bridge-v54.log 2>&1\n'''
replace_once(pi_bridge, old_launch, new_launch)

# Use a fresh endpoint so every broken/stale bridge from previous test builds is
# irrelevant. Repeated Connect presses on this same build may encounter the
# already-running v5.4 bridge; accepting the same bridge version is intentional.
replace_once(pi_bridge, "private val port = 17647", "private val port = 17648")
replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.9"',
    'private val expectedBridgeVersion = "2026-09-09.10"',
)
replace_once(bridge, "PI_ANDROID_PORT || 17647", "PI_ANDROID_PORT || 17648")
replace_once(
    bridge,
    'const bridgeVersion = "2026-09-09.9";',
    'const bridgeVersion = "2026-09-09.10";',
)

text = extras.read_text(encoding="utf-8")
if "17647" not in text:
    raise SystemExit("expected helper port 17647 not found after v5.3")
extras.write_text(text.replace("17647", "17648"), encoding="utf-8")

# Instance IDs were introduced to reject stale bridges on a reused port. v5.4
# has a fresh port/version and deliberately reuses an already-running bridge from
# the SAME build on reconnect. Version equality is sufficient and prevents a
# second launch (which would naturally fail with EADDRINUSE) from blocking UI.
replace_once(
    pi_bridge,
    "if (version == expectedBridgeVersion && instance == expectedBridgeInstance) {",
    "if (version == expectedBridgeVersion) {",
)

print("Applied PiTouch v5.4 robust Termux launcher")
