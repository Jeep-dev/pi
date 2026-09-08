from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:220]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


pi_bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")

# Termux RUN_COMMAND was given a PendingIntent that points back to MainActivity.
# When the Termux command finishes, Termux fires that PendingIntent, which brings
# MainActivity back/recreates it and resets all Compose state to Disconnected.
# This exactly matches the observed behavior: tap Connect -> wait ~1s -> silently
# return to Disconnected with no failure message. The bridge launch is background
# work and does not need a callback Activity, so remove the callback completely.
replace_once(pi_bridge, "import android.app.PendingIntent\n", "")

old = '''            val callback = Intent(context, MainActivity::class.java)\n            val pending = PendingIntent.getActivity(\n                context,\n                nextId++,\n                callback,\n                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE\n            )\n            val intent = Intent("com.termux.RUN_COMMAND").setClassName(termux, service)\n                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")\n                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))\n                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)\n                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)\n            context.startService(intent)\n'''
new = '''            val intent = Intent("com.termux.RUN_COMMAND").setClassName(termux, service)\n                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")\n                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))\n                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)\n            context.startService(intent)\n'''
replace_once(pi_bridge, old, new)

print("Applied PiTouch v5.0 Termux callback relaunch fix")
