package dev.weakbridge

import dev.weakbridge.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattQueueTest {
    @Test
    fun serializesAndRejectsUnrelatedCallbacks() = runTest {
        val queue = BleGattOperationQueue { error("Unexpected fatal") }
        val starts = mutableListOf<Int>()
        val a = async {
            queue.run("a") {
                starts += 1
                true
            }
        }
        val b = async {
            queue.run("b") {
                starts += 2
                true
            }
        }
        runCurrent()
        assertEquals(listOf(1), starts)
        queue.complete("b", 0)
        runCurrent()
        assertFalse(a.isCompleted)
        queue.complete("a", 0)
        runCurrent()
        assertEquals(listOf(1, 2), starts)
        queue.complete("b", 0)
        a.await()
        b.await()
    }

    @Test
    fun statusFailurePropagatesAndQueueCanProceed() = runTest {
        val queue = BleGattOperationQueue {}
        val result = async { runCatching { queue.run("read") { true } } }
        runCurrent()
        queue.complete("read", 5)
        assertEquals(5, (result.await().exceptionOrNull() as GattFailure).status)
        val next = async { queue.run("next") { true } }
        runCurrent()
        queue.complete("next", 0)
        next.await()
    }

    @Test
    fun timeoutPoisonsQueueAndLateCallbackCannotResumeIt() = runTest {
        var fatal = 0
        val queue = BleGattOperationQueue(100) { fatal++ }
        val a = async { runCatching { queue.run("a") { true } } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertTrue(a.await().isFailure)
        assertEquals(1, fatal)
        queue.complete("a", 0)
        assertTrue(runCatching { queue.run("a") { error("Must not start") } }.isFailure)
    }

    @Test
    fun disconnectCancelsPendingAndWaitingOperations() = runTest {
        val queue = BleGattOperationQueue {}
        val a = async { runCatching { queue.run("a") { true } } }
        val b = async { runCatching { queue.run("b") { true } } }
        runCurrent()
        queue.close()
        runCurrent()
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
    }

    @Test
    fun queuedValidityIsCheckedAfterEarlierOperationCompletes() = runTest {
        val queue = BleGattOperationQueue { error("Unexpected fatal") }
        var current = true
        var obsoleteStarted = false
        val first = async { queue.run("first") { true } }
        val obsolete = async {
            queue.runIfCurrent("obsolete", { current }) {
                obsoleteStarted = true
                true
            }
        }
        val next = async { queue.run("next") { true } }
        runCurrent()
        current = false
        queue.complete("first", 0)
        runCurrent()
        first.await()
        assertNull(obsolete.await())
        assertFalse(obsoleteStarted)
        queue.complete("next", 0)
        next.await()
    }
}
