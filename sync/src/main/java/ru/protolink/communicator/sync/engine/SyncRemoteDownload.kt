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
}
