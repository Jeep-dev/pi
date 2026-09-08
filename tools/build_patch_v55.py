from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:260]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = Path("app/src/main/java/com/piandroid/MainActivity.kt")

# UI-only patch. Do not touch PiBridge, the Termux launcher, bridge port/version,
# RPC wiring, or process lifecycle. v5.4 is the known-good connection baseline.
replace_once(
    main,
    "import androidx.compose.material3.OutlinedTextField\n",
    "import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.OutlinedTextFieldDefaults\n",
)
replace_once(
    main,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.platform.LocalFocusManager\nimport androidx.compose.ui.platform.LocalSoftwareKeyboardController\n",
)

old_composer = r'''@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    onValue: (String) -> Unit,
    onPrimary: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF050607)).border(1.dp, Color(0xFF19232C)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (busy) "Pi 正在工作，可点右侧停止" else "输入消息或 / 命令…", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onPrimary() })
        )
        Button(
            onClick = onPrimary,
            modifier = Modifier.height(48.dp),
            enabled = busy || value.isNotBlank(),
            colors = if (busy) ButtonDefaults.buttonColors(containerColor = Color(0xFF6B3030)) else ButtonDefaults.buttonColors()
        ) {
            Text(if (busy) "■" else "↵", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
        }
    }
}
'''

new_composer = r'''@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    onValue: (String) -> Unit,
    onPrimary: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submitAndDismissKeyboard = {
        onPrimary()
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    Row(
        Modifier.fillMaxWidth().background(Color(0xFF050607)).border(1.dp, Color(0xFF19232C)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (busy) "Pi 正在工作，可点右侧停止" else "输入消息或 / 命令…", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitAndDismissKeyboard() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )
        Button(
            onClick = submitAndDismissKeyboard,
            modifier = Modifier.height(48.dp),
            enabled = busy || value.isNotBlank(),
            colors = if (busy) ButtonDefaults.buttonColors(containerColor = Color(0xFF6B3030)) else ButtonDefaults.buttonColors()
        ) {
            Text(if (busy) "■" else "↵", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
        }
    }
}
'''
replace_once(main, old_composer, new_composer)

print("Applied PiTouch v5.5 composer polish: no blue focus border + dismiss IME after send")
