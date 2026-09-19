package com.piandroid

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PiTaskGateTest {
    @Test
    fun stopCancelsUnstartedWorkAndTheFenceRejectsNewWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = PiTaskGate()
        val release = CompletableDeferred<Unit>()
        val executed = AtomicInteger(0)
        val first = gate.launch(scope) {
            release.await()
            executed.incrementAndGet()
        }
        assertNotNull(first)

        gate.beginStop()
        assertNull(gate.launch(scope) { executed.incrementAndGet() })
        release.complete(Unit)
        withTimeout(2_000) { first!!.join() }
        assertEquals(0, executed.get())

        gate.finishStop()
        val accepted = gate.launch(scope) { executed.incrementAndGet() }
        assertNotNull(accepted)
        withTimeout(2_000) { accepted!!.join() }
        assertEquals(1, executed.get())
        scope.cancel()
    }

    @Test
    fun stopIsIdempotentForTheSameGeneration() {
        val gate = PiTaskGate()
        gate.beginStop()
        gate.beginStop()
        assertFalse(gate.isAccepting())
        gate.finishStop()
        assertEquals(true, gate.isAccepting())
    }
}
