from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:220]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
bridge = Path("app/src/main/assets/pi-android-bridge.mjs")

# Robustly distinguish the bridge instance launched by THIS app connection attempt
# from a stale bridge left behind by the previous APK. This removes the need to
# allocate a new TCP port for every UI-only release.
replace_once(
    pi_bridge,
    "import java.net.URLEncoder\n",
    "import java.net.URLEncoder\nimport java.util.UUID\n",
)

replace_once(
    pi_bridge,
    '''    private val port = 17646\n    private val expectedBridgeVersion = "2026-09-09.6"\n    private var nextId = 3000\n''',
    '''    private val port = 17646\n    private val expectedBridgeVersion = "2026-09-09.7"\n    private var expectedBridgeInstance = ""\n    private var nextId = 3000\n''',
)

replace_once(
    pi_bridge,
    '''        runCatching {\n            val bridge = context.assets.open("pi-android-bridge.mjs").use {\n''',
    '''        runCatching {\n            expectedBridgeInstance = UUID.randomUUID().toString()\n            val instanceId = expectedBridgeInstance\n            val bridge = context.assets.open("pi-android-bridge.mjs").use {\n''',
)

replace_once(
    pi_bridge,
    '''                if [ -f ~/.pi/android/bridge.pid ]; then kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true; fi &&\n                sleep 0.35 &&\n                PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs > ~/.pi/android/bridge.log 2>&1 &\n''',
    '''                if [ -f ~/.pi/android/bridge.pid ]; then kill "\\$(cat ~/.pi/android/bridge.pid)" 2>/dev/null || true; fi &&\n                sleep 1 &&\n                PI_ANDROID_INSTANCE='$instanceId' PI_ANDROID_PORT=$port nohup node ~/.pi/android/bridge.mjs > ~/.pi/android/bridge.log 2>&1 &\n''',
)

old_wait = '''        var lastSeenVersion = ""\n        repeat(attempts) {\n            request("/health", null, 1200).onSuccess { raw ->\n                val version = runCatching { JSONObject(raw).optString("bridgeVersion") }.getOrDefault("")\n                lastSeenVersion = version\n                if (version == expectedBridgeVersion) return Result.success(Unit)\n            }\n            delay(250)\n        }\n        val detail = if (lastSeenVersion.isBlank()) {\n            "没有检测到新版 bridge"\n        } else {\n            "检测到旧 bridge：$lastSeenVersion，期望：$expectedBridgeVersion"\n        }\n'''
new_wait = '''        var lastSeenVersion = ""\n        var lastSeenInstance = ""\n        repeat(attempts) {\n            request("/health", null, 1200).onSuccess { raw ->\n                val root = runCatching { JSONObject(raw) }.getOrNull()\n                val version = root?.optString("bridgeVersion").orEmpty()\n                val instance = root?.optString("instanceId").orEmpty()\n                lastSeenVersion = version\n                lastSeenInstance = instance\n                if (version == expectedBridgeVersion && instance == expectedBridgeInstance) {\n                    return Result.success(Unit)\n                }\n            }\n            delay(250)\n        }\n        val detail = when {\n            lastSeenVersion.isBlank() -> "没有检测到新版 bridge"\n            lastSeenVersion != expectedBridgeVersion -> "检测到旧 bridge：$lastSeenVersion，期望：$expectedBridgeVersion"\n            else -> "仍连接到旧 bridge 实例：$lastSeenInstance，正在等待：$expectedBridgeInstance"\n        }\n'''
replace_once(pi_bridge, old_wait, new_wait)

replace_once(
    bridge,
    '''const bridgeVersion = "2026-09-09.6";\n''',
    '''const bridgeVersion = "2026-09-09.7";\nconst instanceId = process.env.PI_ANDROID_INSTANCE || "";\n''',
)

replace_once(
    bridge,
    '''        bridgeVersion,\n        piRunning: !!child && child.exitCode == null,\n''',
    '''        bridgeVersion,\n        instanceId,\n        piRunning: !!child && child.exitCode == null,\n''',
)

print("Applied PiTouch v4.9 bridge instance handshake")
