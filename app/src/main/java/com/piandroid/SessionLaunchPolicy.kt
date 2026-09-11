package com.piandroid

private val existingSessionArgument = Regex(
    "(^|\\s)(--session(?:=|\\s)|--session-id(?:=|\\s)|--continue(?:\\s|$)|-c(?:\\s|$)|--resume(?:=|\\s|$)|-r(?:\\s|$)|--fork(?:=|\\s))"
)

/** True when Pi was explicitly asked to load, continue, resume, or fork prior state. */
internal fun selectsExistingPiSession(command: String): Boolean =
    existingSessionArgument.containsMatchIn(command)

private fun splitLaunchArguments(command: String): List<String>? {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (character in command) {
        if (escaped) {
            current.append(character)
            escaped = false
        } else if (character == '\\' && quote != '\'') {
            escaped = true
        } else if (quote != null) {
            if (character == quote) quote = null else current.append(character)
        } else if (character == '\'' || character == '"') {
            quote = character
        } else if (character.isWhitespace()) {
            if (current.isNotEmpty()) {
                arguments += current.toString()
                current.clear()
            }
        } else {
            current.append(character)
        }
    }
    if (escaped) current.append('\\')
    if (quote != null) return null
    if (current.isNotEmpty()) arguments += current.toString()
    return arguments
}

private val safeLaunchArgument = Regex("[A-Za-z0-9_./~:@%+=,-]+")

private fun quoteLaunchArgument(value: String): String =
    if (value.isNotEmpty() && safeLaunchArgument.matches(value)) value
    else "'${value.replace("'", "'\\''")}'"

/**
 * Pin a direct Pi launch command to the session that was active immediately
 * before disconnect. This deliberately removes stale startup selectors: after
 * /resume or an extension-driven switch, replaying the original --session was
 * the reason reconnect could open an unrelated conversation.
 */
internal fun pinPiLaunchToSession(command: String, sessionFile: String): String {
    if (sessionFile.isBlank()) return command
    val arguments = splitLaunchArguments(command) ?: return command
    if (arguments.isEmpty() || arguments.first().substringAfterLast('/') != "pi") return command

    val retained = mutableListOf<String>()
    var index = 0
    var parsingOptions = true
    while (index < arguments.size) {
        val argument = arguments[index]
        if (!parsingOptions) {
            retained += argument
            index++
            continue
        }
        when {
            argument == "--" -> {
                parsingOptions = false
                retained += argument
                index++
            }
            argument == "--session" || argument == "--session-id" || argument == "--fork" -> index += 2
            argument.startsWith("--session=") || argument.startsWith("--session-id=") || argument.startsWith("--fork=") -> index++
            argument == "--continue" || argument == "-c" -> index++
            argument == "--resume" || argument == "-r" -> {
                index++
                if (index < arguments.size && !arguments[index].startsWith("-")) index++
            }
            argument.startsWith("--resume=") -> index++
            else -> {
                retained += argument
                index++
            }
        }
    }
    val optionTerminator = retained.indexOf("--")
    if (optionTerminator >= 0) retained.addAll(optionTerminator, listOf("--session", sessionFile))
    else retained += listOf("--session", sessionFile)
    return retained.joinToString(" ", transform = ::quoteLaunchArgument)
}

/** Android's preferred model is only valid for a genuinely new, empty session. */
internal fun shouldApplyAndroidDefaultModel(command: String, messageCount: Int): Boolean =
    messageCount == 0 && !selectsExistingPiSession(command)
