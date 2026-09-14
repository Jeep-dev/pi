package com.piandroid

private val existingSessionArgument = Regex(
    "(^|\\s)(--session(?:=|\\s)|--session-id(?:=|\\s)|--continue(?:\\s|$)|-c(?:\\s|$)|--resume(?:=|\\s|$)|-r(?:\\s|$)|--fork(?:=|\\s))"
)

/** True when Pi was explicitly asked to load, continue, resume, or fork prior state. */
internal fun selectsExistingPiSession(command: String): Boolean =
    existingSessionArgument.containsMatchIn(command)

internal enum class ReconnectDecision { ATTACH, RESTART }

/** Decide whether an existing runtime can serve the configured working directory. */
internal fun reconnectDecision(configuredCwd: String, runtimeCwd: String): ReconnectDecision =
    if (configuredCwd.trim() == runtimeCwd.trim()) ReconnectDecision.ATTACH else ReconnectDecision.RESTART

internal fun parseShellArguments(command: String): List<String>? {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var tokenStarted = false
    for (character in command) {
        if (escaped) {
            current.append(character)
            escaped = false
            tokenStarted = true
        } else if (character == '\\' && quote != '\'') {
            escaped = true
            tokenStarted = true
        } else if (quote != null) {
            if (character == quote) quote = null else current.append(character)
            tokenStarted = true
        } else if (character == '\'' || character == '"') {
            quote = character
            tokenStarted = true
        } else if (character.isWhitespace()) {
            if (tokenStarted) {
                arguments += current.toString()
                current.clear()
                tokenStarted = false
            }
        } else {
            current.append(character)
            tokenStarted = true
        }
    }
    if (escaped) {
        current.append('\\')
        tokenStarted = true
    }
    if (quote != null) return null
    if (tokenStarted) arguments += current.toString()
    return arguments
}

private val safeLaunchArgument = Regex("[A-Za-z0-9_./~:@%+=,-]+")
private val protectedStartupOptions = setOf(
    "--mode", "-p", "--print",
    "--session", "--session-id", "--continue", "-c", "--resume", "-r", "--fork",
    "--session-dir", "--no-session", "--api-key"
)
private val startupOptionsWithValues = setOf(
    "--system-prompt", "--append-system-prompt", "--provider", "--model", "--models",
    "--thinking", "--name", "-n", "--api-key", "--session", "--session-id", "--fork",
    "--session-dir", "--extension", "-e", "--skill", "--prompt-template", "--theme",
    "--tools", "-t", "--exclude-tools", "-xt", "--tui-mode", "--use-theme"
)

/** Return a user-facing error when startup arguments would replace App-owned Pi options. */
internal fun startupArgumentsError(startupArguments: String): String? {
    val arguments = parseShellArguments(startupArguments) ?: return "启动参数的引号未闭合"
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        val option = argument.substringBefore('=')
        if (option in protectedStartupOptions) {
            return "启动参数不能覆盖 App 管理的 $option 参数"
        }
        index += if (argument in startupOptionsWithValues && index + 1 < arguments.size) 2 else 1
    }
    return null
}

/** Append validated per-Session options without allowing them to replace App-owned options. */
internal fun appendPiStartupArguments(baseCommand: String, startupArguments: String): String {
    val base = baseCommand.trim()
    if (base.isBlank()) throw IllegalArgumentException("Pi 启动命令不能为空")
    if (startupArguments.isBlank()) return base
    startupArgumentsError(startupArguments)?.let { throw IllegalArgumentException(it) }
    val arguments = parseShellArguments(startupArguments).orEmpty()
    if (arguments.isEmpty()) return base
    return "$base ${arguments.joinToString(" ", transform = ::quoteLaunchArgument)}"
}

private fun quoteLaunchArgument(value: String): String =
    if (value.isNotEmpty() && safeLaunchArgument.matches(value)) value
    else "'${value.replace("'", "'\\''")}'"

private fun withoutPiSessionArguments(command: String): List<String>? {
    val arguments = parseShellArguments(command) ?: return null
    if (arguments.isEmpty() || arguments.first().substringAfterLast('/') != "pi") return null

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
    return retained
}

/** Remove session selectors so a new cwd always starts a new Pi session. */
internal fun launchPiWithoutSession(command: String): String {
    val retained = withoutPiSessionArguments(command) ?: return command
    return retained.joinToString(" ", transform = ::quoteLaunchArgument)
}

/**
 * Give a newly created Android Session a native Pi identity immediately.
 * --session-id is exact (and creates the file when absent), while --session-dir
 * keeps equal cwd values from ever sharing Pi's default project session folder.
 */
internal fun ensurePiSessionIdentity(command: String, sessionId: String, sessionDirectory: String): String {
    if (sessionId.isBlank() || sessionDirectory.isBlank()) return command
    val source = withoutPiSessionArguments(command)?.toMutableList() ?: return command
    val optionTerminator = source.indexOf("--")
    val retained = mutableListOf<String>()
    var index = 0
    while (index < source.size) {
        if (optionTerminator >= 0 && index >= optionTerminator) {
            retained += source[index]
            index++
            continue
        }
        when {
            source[index] == "--session-dir" -> index += 2
            source[index].startsWith("--session-dir=") -> index++
            else -> {
                retained += source[index]
                index++
            }
        }
    }
    val insertionPoint = retained.indexOf("--").takeIf { it >= 0 } ?: retained.size
    retained.addAll(insertionPoint, listOf("--session-dir", sessionDirectory, "--session-id", sessionId))
    return retained.joinToString(" ", transform = ::quoteLaunchArgument)
}

/**
 * Pin a direct Pi launch command to the session that was active immediately
 * before disconnect. This deliberately removes stale startup selectors: after
 * /resume or an extension-driven switch, replaying the original --session was
 * the reason reconnect could open an unrelated conversation.
 */
internal fun pinPiLaunchToSession(command: String, sessionFile: String): String {
    if (sessionFile.isBlank()) return command
    val retained = (withoutPiSessionArguments(command) ?: return command).toMutableList()
    val optionTerminator = retained.indexOf("--")
    if (optionTerminator >= 0) retained.addAll(optionTerminator, listOf("--session", sessionFile))
    else retained += listOf("--session", sessionFile)
    return retained.joinToString(" ", transform = ::quoteLaunchArgument)
}

/** Android's preferred model is only valid for a genuinely new, empty session. */
internal fun shouldApplyAndroidDefaultModel(command: String, messageCount: Int): Boolean =
    messageCount == 0 && !selectsExistingPiSession(command)
