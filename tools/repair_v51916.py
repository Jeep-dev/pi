from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text()
old = '''    fun finalizeAssistant(text: String, stopReason: String, errorMessage: String) {
        if (text.isNotBlank()) {
            val index = lines.indexOfLast { it.role == "assistant" && it.streaming }
            if (index >= 0) lines[index] = lines[index].copy(text = text, streaming = false)
            else if (lines.lastOrNull { it.role == "assistant" }?.text != text) lines.add(ChatLine("assistant", text))
        }
        assistantCompletionNotice(stopReason, text, errorMessage)?.let(::addSystem)
    }
'''
new = '''    fun finalizeAssistant(text: String, stopReason: String, errorMessage: String) {
        if (text.isNotBlank()) {
            val index = lines.indexOfLast { it.role == "assistant" && it.streaming }
            if (index >= 0) {
                lines[index] = lines[index].copy(text = text, streaming = false)
            } else {
                // A normal visible answer always creates a streaming assistant row from
                // text_delta before message_end. A final-only message_end after history/
                // recovery has no current owner and used to append stale old answers.
                Log.w("PiChatEvents", "Dropping orphan assistant message_end")
            }
        }
        assistantCompletionNotice(stopReason, text, errorMessage)?.let(::addSystem)
    }
'''
main = replace_once(main, old, new, "orphan final assistant guard")
MAIN.write_text(main)
print("v5.19.16 orphan message_end guard applied")
