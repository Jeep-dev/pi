from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:260]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")

# Prevent double-taps on Connect from launching two bridge replacement flows
# that kill each other's bridge/Pi processes.
replace_once(
    main,
    '''    var connected by remember { mutableStateOf(false) }\n    var status by remember { mutableStateOf("Disconnected") }\n''',
    '''    var connected by remember { mutableStateOf(false) }\n    var connecting by remember { mutableStateOf(false) }\n    var status by remember { mutableStateOf("Disconnected") }\n''',
)

replace_once(
    main,
    '''    var cursor by remember { mutableLongStateOf(0L) }\n''',
    '''    var cursor by remember { mutableLongStateOf(0L) }\n    var eventFailures by remember { mutableStateOf(0) }\n''',
)

replace_once(
    main,
    '''    val connect: () -> Unit = {\n        scope.launch {\n            status = "Installing bridge"\n''',
    '''    val connect: () -> Unit = connect@{\n        if (connecting) return@connect\n        connecting = true\n        scope.launch {\n            status = "Installing bridge"\n''',
)

# The nested Result folds always return to this point. Release the connect lock
# after either success or failure so the user can deliberately retry.
replace_once(
    main,
    '''            )\n        }\n    }\n\n    fun sendExtensionCommand(text: String) {\n''',
    '''            )\n            connecting = false\n        }\n    }\n\n    fun sendExtensionCommand(text: String) {\n''',
)

# One missed localhost poll must not tear down a healthy session. Android can
# briefly delay localhost while the app/Termux changes lifecycle state. Require
# five consecutive event poll failures before declaring the bridge disconnected.
replace_once(
    main,
    '''            bridge.events(cursor).onSuccess { batch ->\n                cursor = batch.latest\n''',
    '''            bridge.events(cursor).onSuccess { batch ->\n                eventFailures = 0\n                cursor = batch.latest\n''',
)

replace_once(
    main,
    '''            }.onFailure {\n                status = "Disconnected"\n                connected = false\n                addSystem("Bridge 连接中断：${it.message}")\n            }\n            delay(160)\n''',
    '''            }.onFailure { error ->\n                eventFailures += 1\n                if (eventFailures >= 5) {\n                    status = "Disconnected"\n                    connected = false\n                    addSystem("Bridge 连接中断（连续 $eventFailures 次）：${error.message}")\n                }\n            }\n            delay(250)\n''',
)

# While connecting, show the actual state instead of leaving a misleading
# tappable-looking Connect label on screen.
replace_once(
    main,
    '''                        if (connected) "⋮" else "Connect",\n''',
    '''                        if (connected) "⋮" else if (status == "Disconnected" || status.endsWith("failed")) "Connect" else "Connecting…",\n''',
)

print("Applied PiTouch v5.2 connection resilience patch")
