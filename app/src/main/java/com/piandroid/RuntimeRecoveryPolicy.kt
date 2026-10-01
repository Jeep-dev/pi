package com.piandroid

internal fun canReplaceUnreachableBridge(connectionRefused: Boolean, foreignOwner: Boolean): Boolean =
    connectionRefused || foreignOwner

internal fun canReplaceRunningBridge(streaming: Boolean?, compacting: Boolean?): Boolean =
    streaming == false && compacting == false

internal fun confirmedOffline(piRunning: Boolean?, connectionRefused: Boolean): Boolean =
    piRunning == false || (piRunning == null && connectionRefused)
