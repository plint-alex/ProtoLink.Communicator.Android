package ru.protolink.communicator.sync.ports

import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.model.SyncItemMeta
import java.time.Instant

interface MetadataStore {
    fun getAll(mappingId: String): List<SyncItemMeta>
    fun getAllRemoteIds(): Set<String>
    fun findByRemoteId(remoteId: String): SyncItemMeta?
    fun upsert(item: SyncItemMeta)
    fun delete(mappingId: String, relativePath: String)
    fun deleteByRemoteId(remoteId: String)
    fun getLastSyncUtc(mappingId: String): Instant?
    fun setLastSyncUtc(mappingId: String, time: Instant)
    fun clearMapping(mappingId: String)
}

interface LocalFileSystem {
    fun snapshot(rootPath: String): List<FsEntry>
    fun createDirectory(rootPath: String, relativePath: String)
    fun writeFile(rootPath: String, relativePath: String, bytes: ByteArray)
    fun readFile(rootPath: String, relativePath: String): ByteArray
    fun delete(rootPath: String, relativePath: String, isFolder: Boolean)
    fun move(rootPath: String, fromRelative: String, toRelative: String, isFolder: Boolean)
    fun exists(rootPath: String, relativePath: String): Boolean
}

interface RemoteCloud {
    suspend fun listChildrenPaged(parentId: String): List<RemoteEntry>
    suspend fun addFolder(parentId: String, name: String): String
    suspend fun addFile(parentId: String, name: String, bytes: ByteArray, mimeType: String): String
    suspend fun uploadFile(entityId: String, name: String, bytes: ByteArray, mimeType: String)
    suspend fun downloadFile(entityId: String): ByteArray?
    suspend fun fileSize(entityId: String): Long?
    suspend fun rename(entityId: String, newName: String)
    suspend fun move(entityId: String, oldParentId: String, newParentId: String)
    suspend fun delete(entityId: String)
}
