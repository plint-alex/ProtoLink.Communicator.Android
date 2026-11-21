package ru.protolink.communicator.syncadapt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

/**
 * Live 1.0.60: Force waited 600s on SyncFlight while a non-preemptible reconcile held the mutex.
 * Live 1.0.64: Job cancel alone left mutex held >120s when reconcile only checked between phases.
 * Live 1.0.65: preempt cancelled the mutex-owning Job; structured joins blocked finally/unlock
 * despite OkHttp Canceled — Force still timed out at 120s.
 * Force must cancel/abandon detached work and acquire promptly (Win CTS preempt parity).
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

    /**
     * Models mid-reconcile under [SyncFlight.runDeferringToForce]. Mutex owner is NOT the
     * preempt target — Force must win ≪120s without waiting out the loop.
     */
    @Test
    fun acquireForForce_whileLongReconcile_yieldsWithinShortBound() = runTest {
        val holder = launch {
            SyncFlight.mutex.lock()
            try {
                SyncFlight.runDeferringToForce(pollMs = 20L, abandonAfterMs = 500L) {
                    // Simulate thousands of compare-phase items; each checks cancellation.
                    repeat(50_000) {
                        ensureActive()
                        delay(5)
                    }
                    "done"
                }
            } finally {
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
        yield()
        delay(30)
        assertThat(SyncFlight.mutex.isLocked).isTrue()

        val elapsed = measureTimeMillis {
            val acquired = SyncFlight.acquireForForce(5_000)
            assertThat(acquired).isTrue()
        }
        assertThat(elapsed).isLessThan(2_000L)
        assertThat(SyncFlight.forcePreferred.get()).isTrue()

        SyncFlight.mutex.unlock()
        SyncFlight.clearForcePreferred()
        holder.join()
    }

    /**
     * Live 1.0.65 class: work ignores cancel (NonCancellable hang). Owner must still unlock
     * after abandon grace so Force acquires ≪120s.
     */
    @Test
    fun acquireForForce_abandonsNonCooperativeWorkWithinGrace() = runTest {
        val holder = launch {
            SyncFlight.mutex.lock()
            try {
                SyncFlight.runDeferringToForce(pollMs = 20L, abandonAfterMs = 400L) {
                    withContext(NonCancellable) {
                        delay(60_000)
                    }
                    "never"
                }
            } finally {
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
        yield()
        delay(40)
        assertThat(SyncFlight.mutex.isLocked).isTrue()

        val elapsed = measureTimeMillis {
            val acquired = SyncFlight.acquireForForce(5_000)
            assertThat(acquired).isTrue()
        }
        assertThat(elapsed).isLessThan(2_500L)
        assertThat(SyncFlight.forcePreferred.get()).isTrue()
        assertThat(SyncFlight.laneEpoch.get()).isGreaterThan(0)

        SyncFlight.mutex.unlock()
        SyncFlight.clearForcePreferred()
        holder.join()
    }

    @Test
    fun runDeferringToForce_returnsNullWhenForcePreferred() = runTest {
        val deferred = launch {
            delay(40)
            SyncFlight.forcePreferred.set(true)
        }
        val result = SyncFlight.runDeferringToForce(pollMs = 10L, abandonAfterMs = 500L) {
            delay(10_000)
            "never"
        }
        deferred.join()
        assertThat(result).isNull()
        SyncFlight.clearForcePreferred()
    }

    @Test
    fun runDeferringToForce_returnsNullOnLaneEpochBump() = runTest {
        val deferred = launch {
            delay(40)
            SyncFlight.laneEpoch.incrementAndGet()
        }
        val result = SyncFlight.runDeferringToForce(pollMs = 10L, abandonAfterMs = 500L) {
            delay(10_000)
            "never"
        }
        deferred.join()
        assertThat(result).isNull()
    }

    @Test
    fun cancelBudgetedExclusiveOnly_cancelsBudgetedPush_notUnbudgeted() = runTest {
        // Win CancelBudgetedExclusiveOnly — full Sync coalesce aborts AutoPush only.
        SyncFlight.markBudgetedExclusive(true)
        val push = async {
            SyncFlight.runDeferringToForce(pollMs = 10L, abandonAfterMs = 500L) {
                delay(10_000)
                "push-done"
            }
        }
        delay(40)
        SyncFlight.cancelBudgetedExclusiveOnly()
        assertThat(push.await()).isNull()
        SyncFlight.markBudgetedExclusive(false)

        SyncFlight.markBudgetedExclusive(false)
        val full = async {
            SyncFlight.runDeferringToForce(pollMs = 10L, abandonAfterMs = 500L) {
                delay(80)
                "full-done"
            }
        }
        delay(20)
        SyncFlight.cancelBudgetedExclusiveOnly() // no-op when not budgeted
        assertThat(full.await()).isEqualTo("full-done")
    }
}
