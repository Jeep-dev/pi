package com.piandroid

import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class TransientErrorClassificationTest {
    @Test fun androidErrnoCauseIsRecognizedThroughGenericConnectWrapper() {
        val cause = IllegalStateException("connect failed: ECONNREFUSED (Connection refused)")
        val outer = ConnectException("Failed to connect to /127.0.0.1:17650")
        outer.initCause(cause)
        assertTrue(outer.isConnectRefusedFailure())
        assertTrue(IllegalStateException("request failed", outer).isConnectRefusedFailure())
        assertFalse(IllegalStateException("read failed: ETIMEDOUT").isConnectRefusedFailure())
        assertFalse(IllegalStateException("network failed: ENETUNREACH").isConnectRefusedFailure())
    }

    @Test fun onlyActualRefusalsProveThePortIsClosed() {
        assertTrue(ConnectException("Connection refused").isConnectRefusedFailure())
        assertTrue(IllegalStateException("HTTP", ConnectException("connect failed: ECONNREFUSED")).isConnectRefusedFailure())
        assertFalse(ConnectException("Network is unreachable").isConnectRefusedFailure())
        assertFalse(ConnectException("Connection timed out").isConnectRefusedFailure())
        assertFalse(ConnectException().isConnectRefusedFailure())
        assertFalse(SocketTimeoutException("read timed out").isConnectRefusedFailure())
    }
}
