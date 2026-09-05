package ru.protolink.communicator.syncadapt

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.protolink.communicator.data.MappingStore
import ru.protolink.communicator.sync.engine.SyncEngine
import ru.protolink.communicator.sync.model.SyncMapping
import ru.protolink.communicator.sync.ports.MetadataStore
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mappingStore: MappingStore,
    private val metadataStore: MetadataStore,
    private val remote: ApiRemoteCloud,
    private val localFs: SafLocalFileSystem
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mappings = mappingStore.load().map {
            SyncMapping(
                id = it.cloudFolderId.replace("-", ""),
                cloudFolderId = it.cloudFolderId,
                localRootPath = it.localPath,
                cloudFolderName = it.cloudFolderName
            )
        }
        if (mappings.isEmpty()) {
            Log.i(TAG, "No mappings; skip")
            return Result.success()
        }
        if (!SyncFlight.mutex.tryLock()) {
            SyncFlight.deferredFull.set(true)
            Log.i(TAG, "Skip; another sync is already running (deferred full)")
            return Result.success()
        }
        return try {
            do {
                SyncFlight.deferredFull.set(false)
                Log.i(TAG, "Reconcile ${mappings.size} mapping(s)")
                val result = SyncEngine(metadataStore, localFs, remote).reconcileAll(mappings)
                Log.i(TAG, "Done local=${result.localChanges} remote=${result.remoteChanges}")
            } while (SyncFlight.deferredFull.getAndSet(false))
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        } finally {
            SyncFlight.mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "ProtoLinkSync"
        private const val UNIQUE_PERIODIC = "protolink-cloud-sync"
        private const val UNIQUE_ONESHOT = "protolink-cloud-sync-now"

        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_ONESHOT)
            Log.i(TAG, "Cancelled periodic/oneshot WorkManager sync")
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
}
