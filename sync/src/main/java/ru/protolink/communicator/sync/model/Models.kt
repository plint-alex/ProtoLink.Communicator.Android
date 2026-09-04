package ru.protolink.communicator.sync.model

import java.time.Instant

data class SyncMapping(
    val id: String,
    val cloudFolderId: String,
    val localRootPath: String,
    val cloudFolderName: String = ""
)

data class SyncItemMeta(
    val mappingId: String,
    val remoteId: String,
    val parentRemoteId: String,
    val relativePath: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val remoteUpdateTime: Instant? = null
)

data class FsEntry(
    val relativePath: String,
    val isFolder: Boolean,
    val sizeBytes: Long
)

data class RemoteEntry(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val updateTime: Instant? = null
)

sealed class LocalChange {
    data class Renamed(
        val meta: SyncItemMeta,
        val newRelativePath: String,
        val newParentRemoteId: String,
        val newName: String
    ) : LocalChange()

    data class Added(val entry: FsEntry) : LocalChange()
    data class Removed(val meta: SyncItemMeta) : LocalChange()
    data class Updated(val meta: SyncItemMeta, val newSize: Long) : LocalChange()
}

sealed class RemoteChange {
    data class Renamed(
        val meta: SyncItemMeta,
        val remote: RemoteEntry,
        val newRelativePath: String
    ) : RemoteChange()

    data class Added(val remote: RemoteEntry, val relativePath: String) : RemoteChange()
    data class Removed(val meta: SyncItemMeta) : RemoteChange()
    data class Updated(val meta: SyncItemMeta, val remote: RemoteEntry) : RemoteChange()
}
