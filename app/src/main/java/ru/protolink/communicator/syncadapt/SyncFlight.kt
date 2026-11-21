package ru.protolink.communicator.syncadapt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-flight lock shared by UI sync and [SyncWorker].
 *
 * Force Download/Upload must win the lane promptly (Win CTS preempt parity).
 *
 * Live 1.0.65 FAIL: preempt cancelled the **mutex-owning** Job. Structured concurrency then
 * waited for reconcile children (OkHttp cancel reached the socket, but `finally`/unlock did not
 * run until the whole tree finished) → FORCE_SYNC_LOCK_TIMEOUT at 120s. Fix: bind preempt to a
 * **detached** work Job; after Force prefers, abandon that work within [ABANDON_AFTER_MS] so the
 * owner unlocks; [laneEpoch] makes abandoned writers stop at the next check.
 */
object SyncFlight {
    val mutex = Mutex()
    /** SignalR/full sync requested while another sync held the lock — run one full pass after. */
    val deferredFull = AtomicBoolean(false)
    /** Force (or conflict resolve) wants the exclusive lane — full/push must defer. */
    val forcePreferred = AtomicBoolean(false)
    /**
     * Win CloudMappedSyncCoordinator._cloudMutationSuspend — pause interval AutoPush while
     * Cloud delete + mirror cleanup runs so PushLocalChanges cannot recreate soft-hidden rows.
     */
    private val cloudMutationSuspend = AtomicInteger(0)
    /**
     * Bumped on Force preempt. Full/push work captures the epoch at start and aborts when it
     * changes so an abandoned (post-grace) writer cannot keep mutating under Force.
     */
    val laneEpoch = AtomicInteger(0)

    private val exclusiveJob = AtomicReference<Job?>(null)
    /**
     * Win CloudMappedSyncCoordinator._budgetedExclusive — AutoPush/interval push only.
     * Full reconcile is unlimited; [cancelBudgetedExclusiveOnly] must not abort it.
     */
    private val budgetedExclusive = AtomicBoolean(false)

    /** Grace after cancel before owner unlocks even if work ignores cancellation. */
    const val ABANDON_AFTER_MS = 2_000L

    fun bindExclusiveJob(job: Job) {
        exclusiveJob.set(job)
    }

    fun clearExclusiveJob(job: Job) {
        exclusiveJob.compareAndSet(job, null)
    }

    fun markBudgetedExclusive(budgeted: Boolean) {
        budgetedExclusive.set(budgeted)
    }

    /**
     * Win CancelBudgetedExclusiveOnly — cancel hung AutoPush so deferred full Sync can reclaim.
     * Does **not** set [forcePreferred] (manual Sync / SignalR coalesce, not Force).
     */
    fun cancelBudgetedExclusiveOnly() {
        if (!budgetedExclusive.get()) return
        exclusiveJob.get()?.cancel(CancellationException("SyncFlight: CancelBudgetedExclusiveOnly"))
    }

    /** Mark Force priority, bump lane epoch, cancel current exclusive **work** (not mutex owner). */
    fun preemptExclusiveForForce() {
        forcePreferred.set(true)
        laneEpoch.incrementAndGet()
        exclusiveJob.get()?.cancel(CancellationException("SyncFlight: Force preempt"))
    }

    fun clearForcePreferred() {
        forcePreferred.set(false)
    }

    fun suspendCloudMutation() {
        cloudMutationSuspend.incrementAndGet()
    }

    fun resumeCloudMutation() {
        if (cloudMutationSuspend.decrementAndGet() < 0) cloudMutationSuspend.set(0)
    }

    fun isCloudMutationSuspended(): Boolean = cloudMutationSuspend.get() > 0

    /**
     * Run full/push work until complete, or yield to Force.
     *
     * Work runs on a **SupervisorJob with no parent link to the mutex owner**, so Force cancel /
     * abandon cannot trap the owner's `finally` behind structured child joins (1.0.65).
     * Returns null when yielded to Force. Do **not** wrap Force's own pull/push.
     */
    suspend fun <T> runDeferringToForce(
        pollMs: Long = 50L,
        abandonAfterMs: Long = ABANDON_AFTER_MS,
        block: suspend () -> T
    ): T? {
        val startEpoch = laneEpoch.get()
        // Detached supervisor: cancel/abandon must not block the mutex-holding caller.
        val workJob = SupervisorJob()
        val worker = CoroutineScope(
            Dispatchers.IO + workJob + CoroutineName("SyncFlightWork")
        ).async {
            ensureLaneActive(startEpoch)
            block()
        }
        bindExclusiveJob(worker)
        try {
            while (true) {
                val preempted = forcePreferred.get() || laneEpoch.get() != startEpoch
                if (preempted) {
                    // Cancel may already have flipped isActive=false while work is still
                    // finishing under NonCancellable — do not fall through to await() (1.0.65).
                    worker.cancel(CancellationException("SyncFlight: Force preferred"))
                    withTimeoutOrNull(abandonAfterMs) { worker.join() }
                    return null
                }
                if (!worker.isActive) break
                delay(pollMs)
            }
            return try {
                worker.await()
            } catch (e: CancellationException) {
                // Force preempt OR CancelBudgetedExclusiveOnly → yield lane (null).
                if (forcePreferred.get() ||
                    laneEpoch.get() != startEpoch ||
                    e.message?.contains("CancelBudgetedExclusiveOnly") == true
                ) {
                    null
                } else {
                    throw e
                }
            }
        } finally {
            clearExclusiveJob(worker)
            if (worker.isActive) {
                worker.cancel(CancellationException("SyncFlight: work scope closed"))
            }
            // Do not join forever — abandon so mutex owner can unlock.
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

    /** Throw if Force preempted this lane epoch (call from SyncEngine item loops). */
    fun ensureLaneActive(startEpoch: Int) {
        if (forcePreferred.get() || laneEpoch.get() != startEpoch) {
            throw CancellationException("SyncFlight: lane preempted")
        }
    }

    /** Test-only: drop singleton leftovers between unit tests. */
    internal fun resetForTests() {
        exclusiveJob.set(null)
        forcePreferred.set(false)
        deferredFull.set(false)
        budgetedExclusive.set(false)
        laneEpoch.set(0)
        // Mutex must be unlocked by the test that locked it.
    }
}
