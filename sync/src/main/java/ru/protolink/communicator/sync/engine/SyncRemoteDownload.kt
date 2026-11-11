package ru.protolink.communicator.sync.engine

/**
 * Failed / not-found remote download (null from [RemoteCloud.downloadFile]) soft-fails
 * per item during mapped Notes/Cloud reconcile — does not abort the sync pass.
 * Empty remote bytes (`isEmpty()`) are legitimate content and are not a failure.
 */
object SyncRemoteDownload {
    fun logFailed(phase: String, relativePath: String, remoteId: String) {
        System.err.println(
            "[SyncEngine] soft-fail remote download ($phase): relative=$relativePath remoteId=$remoteId"
        )
    }

    /** Local create/write failed for one mapped child — continue the pass (NFR-SYNC-1). */
    fun logMaterializeFailed(phase: String, relativePath: String, remoteId: String, detail: String) {
        System.err.println(
            "[SyncEngine] soft-fail materialize ($phase): relative=$relativePath remoteId=$remoteId detail=$detail"
        )
    }
}
