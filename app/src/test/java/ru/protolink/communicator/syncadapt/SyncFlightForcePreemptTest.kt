package ru.protolink.communicator.syncadapt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * Live 1.0.60: Force waited 600s on SyncFlight while a non-preemptible reconcile held the mutex.
 * Force must cancel the holder and acquire promptly (Win CTS preempt parity).
 */
class SyncFlightForcePreemptTest {

    @After
    fun tearDown() {
        if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
        SyncFlight.resetForTests()
    }

    @Test
    fun acquireForForce_cancelsHolderAndLocks() = runTest {
        val holder = launch {
            SyncFlight.mutex.lock()
            val job = coroutineContext[Job]!!
            SyncFlight.bindExclusiveJob(job)
            try {
                awaitCancellation()
            } finally {
                SyncFlight.clearExclusiveJob(job)
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
        yield()
        assertThat(SyncFlight.mutex.isLocked).isTrue()

        val acquired = SyncFlight.acquireForForce(5_000)
        assertThat(acquired).isTrue()
        assertThat(SyncFlight.forcePreferred.get()).isTrue()
        assertThat(SyncFlight.mutex.isLocked).isTrue()

        SyncFlight.mutex.unlock()
        SyncFlight.clearForcePreferred()
        holder.join()
    }

    @Test
    fun acquireForForce_whenFree_locksImmediately() = runTest {
        val acquired = SyncFlight.acquireForForce(1_000)
        assertThat(acquired).isTrue()
        assertThat(SyncFlight.forcePreferred.get()).isTrue()
        SyncFlight.mutex.unlock()
        SyncFlight.clearForcePreferred()
    }
}
