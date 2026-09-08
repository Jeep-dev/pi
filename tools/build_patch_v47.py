from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
extras = Path("app/src/main/java/com/piandroid/PiBridgeExtras.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")

# v4.6 reused the exact same bridge port/version as v4.5. During an APK upgrade,
# waitForBridge() could briefly accept the old bridge as if it were the new one.
# The old bridge then receives /start, stopPi() SIGTERMs its active Pi child,
# producing exit=143. Give this build a fresh identity/port so a stale bridge can
# never satisfy the health check while it is being replaced.
replace_once(pi_bridge, "private val port = 17645", "private val port = 17646")
replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.5"',
    'private val expectedBridgeVersion = "2026-09-09.6"',
)

replace_once(bridge, "PI_ANDROID_PORT || 17645", "PI_ANDROID_PORT || 17646")
replace_once(bridge, 'const bridgeVersion = "2026-09-09.5";', 'const bridgeVersion = "2026-09-09.6";')

# PiBridgeExtras owns the history/resume helper HTTP calls, so keep every helper
# endpoint on the same fresh bridge port.
text = extras.read_text(encoding="utf-8")
if "17645" not in text:
    raise SystemExit("expected v4.5 helper port 17645 not found")
extras.write_text(text.replace("17645", "17646"), encoding="utf-8")

print("Applied PiTouch v4.7 bridge identity fix")
