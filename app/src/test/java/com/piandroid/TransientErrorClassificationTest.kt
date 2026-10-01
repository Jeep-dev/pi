package com.piandroid

import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class TransientErrorClassificationTest {
    @Test fun onlyActualRefusalsProveThePortIsClosed() {
        assertTrue(ConnectException("Connection refused").isConnectRefusedFailure())
        assertTrue(IllegalStateException("HTTP", ConnectException("connect failed: ECONNREFUSED")).isConnectRefusedFailure())
        assertFalse(ConnectException("Network is unreachable").isConnectRefusedFailure())
        assertFalse(ConnectException("Connection timed out").isConnectRefusedFailure())
        assertFalse(ConnectException().isConnectRefusedFailure())
        assertFalse(SocketTimeoutException("read timed out").isConnectRefusedFailure())
    }
}
