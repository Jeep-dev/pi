from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
extras = Path("app/src/main/java/com/piandroid/PiBridgeExtras.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")

# v5.2 can still see a surviving v4.7 bridge on 17646. The screenshot proves
# that process is alive and occupying the port, so the newly copied bridge cannot
# bind there. Do not fight the stale process during startup: move this build to a
# fresh port. This makes an old 17646 bridge irrelevant even if Android/Termux
# refuses to kill it.
replace_once(pi_bridge, "private val port = 17646", "private val port = 17647")
replace_once(
    pi_bridge,
    'private val expectedBridgeVersion = "2026-09-09.8"',
    'private val expectedBridgeVersion = "2026-09-09.9"',
)
replace_once(bridge, "PI_ANDROID_PORT || 17646", "PI_ANDROID_PORT || 17647")
replace_once(
    bridge,
    'const bridgeVersion = "2026-09-09.8";',
    'const bridgeVersion = "2026-09-09.9";',
)

text = extras.read_text(encoding="utf-8")
if "17646" not in text:
    raise SystemExit("expected helper port 17646 not found after earlier patches")
extras.write_text(text.replace("17646", "17647"), encoding="utf-8")

print("Applied PiTouch v5.3 fresh-port stale-bridge bypass")
