package ru.protolink.communicator.syncadapt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-flight lock shared by UI sync and [SyncWorker].
 *
 * Force Download/Upload must win the lane promptly (Win CTS preempt parity). A long
 * reconcile that only [Mutex.lock]s without cancel leaves Force waiting until timeout —
 * live 1.0.60 cold Force on MyNotesFolder aborted after 600s with getFile=0.
 * Live 1.0.64: Job cancel alone was not enough when SyncEngine only checked cancellation
 * between phases — [runDeferringToForce] cancels the in-flight block when Force prefers.
 */
object SyncFlight {
    val mutex = Mutex()
    /** SignalR/full sync requested while another sync held the lock — run one full pass after. */
    val deferredFull = AtomicBoolean(false)
    /** Force (or conflict resolve) wants the exclusive lane — full/push must defer. */
    val forcePreferred = AtomicBoolean(false)

    private val exclusiveJob = AtomicReference<Job?>(null)

    fun bindExclusiveJob(job: Job) {
        exclusiveJob.set(job)
    }

    fun clearExclusiveJob(job: Job) {
        exclusiveJob.compareAndSet(job, null)
    }

    /** Mark Force priority and cancel the current exclusive holder so it unlocks. */
    fun preemptExclusiveForForce() {
        forcePreferred.set(true)
        exclusiveJob.get()?.cancel(CancellationException("SyncFlight: Force preempt"))
    }

    fun clearForcePreferred() {
        forcePreferred.set(false)
    }

    /**
     * Run full/push work until complete, or cancel it within ~[pollMs] when [forcePreferred]
     * is set so Force can acquire the mutex without waiting out a whole MyNotesFolder pass.
     * Returns null when yielded to Force. Do **not** wrap Force's own pull/push.
     */
    suspend fun <T> runDeferringToForce(pollMs: Long = 50L, block: suspend () -> T): T? =
        coroutineScope {
            val work = async { block() }
            while (work.isActive) {
                if (forcePreferred.get()) {
                    work.cancel(CancellationException("SyncFlight: Force preferred"))
                    break
                }
                delay(pollMs)
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (forcePreferred.get()) null else throw e
            }
        }

    /**
     * Acquire [mutex] for Force: preempt any holder, then wait up to [waitMs].
     * Caller must [Mutex.unlock] in finally when this returns true.
     */
    suspend fun acquireForForce(waitMs: Long = 120_000L): Boolean {
        preemptExclusiveForForce()
        if (mutex.tryLock()) return true
        return withTimeoutOrNull(waitMs) {
            while (true) {
                exclusiveJob.get()?.cancel(CancellationException("SyncFlight: Force preempt"))
                val got = withTimeoutOrNull(250) {
                    mutex.lock()
                    true
                } != null
                if (got) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
    }

    /** Test-only: drop singleton leftovers between unit tests. */
    internal fun resetForTests() {
        exclusiveJob.set(null)
        forcePreferred.set(false)
        deferredFull.set(false)
        // Mutex must be unlocked by the test that locked it.
    }
}
