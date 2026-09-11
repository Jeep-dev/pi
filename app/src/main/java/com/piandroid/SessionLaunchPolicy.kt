package com.piandroid

private val existingSessionArgument = Regex(
    "(^|\\s)(--session(?:=|\\s)|--session-id(?:=|\\s)|--continue(?:\\s|$)|-c(?:\\s|$)|--resume(?:=|\\s|$)|-r(?:\\s|$)|--fork(?:=|\\s))"
)

/** True when Pi was explicitly asked to load, continue, resume, or fork prior state. */
internal fun selectsExistingPiSession(command: String): Boolean =
    existingSessionArgument.containsMatchIn(command)

/** Android's preferred model is only valid for a genuinely new, empty session. */
internal fun shouldApplyAndroidDefaultModel(command: String, messageCount: Int): Boolean =
    messageCount == 0 && !selectsExistingPiSession(command)
