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
    /**
     * Tree snapshot under [rootPath].
     * Win SnapshotFs([computeContentHashes]): reconcile ApplyLocal uses false (size/structure
     * only — content already verified in ApplyCompare); interval push uses true so same-size
     * edits still classify as Updated. Default true preserves Force/push callers.
     */
    fun snapshot(rootPath: String, computeContentHashes: Boolean = true): List<FsEntry>
    /**
     * Immediate children only (no recursion, no content hash). Empty [relativePath] = root.
     * Force uses this for early per-parent ghost prune without a full hashed snapshot.
     */
    fun listImmediate(rootPath: String, relativePath: String = ""): List<FsEntry>
    fun createDirectory(rootPath: String, relativePath: String)
    fun writeFile(rootPath: String, relativePath: String, bytes: ByteArray)
    fun readFile(rootPath: String, relativePath: String): ByteArray
    fun delete(rootPath: String, relativePath: String, isFolder: Boolean)
    fun move(rootPath: String, fromRelative: String, toRelative: String, isFolder: Boolean)
    fun exists(rootPath: String, relativePath: String): Boolean

    /**
     * Byte length of a local file, or null if missing / not a file.
     * Win ApplyCompare uses File.Exists + FileInfo.Length — not a full-tree snapshot.
     * Default reads bytes (fine for unit fakes); production SAF overrides with cheap length.
     */
    fun fileLengthBytes(rootPath: String, relativePath: String): Long? =
        runCatching {
            if (!exists(rootPath, relativePath)) return null
            readFile(rootPath, relativePath).size.toLong()
        }.getOrNull()
}

interface RemoteCloud {
    /**
     * List CloudFolder/CloudFile children. [includeValues]=false is structure-only
     * (ids+codes; names may be synthetic) for warm WalkRemote — Win values-light parity.
     */
    suspend fun listChildrenPaged(parentId: String, includeValues: Boolean = true): List<RemoteEntry>

    /**
     * List children of many parents in one GetEntities call (API ParentIdsMatchAny=OR).
     * Each [RemoteEntry.parentId] is the matched folder (MainParentId). Default falls back
     * to sequential [listChildrenPaged] so unit fakes / old servers keep working.
     */
    suspend fun listChildrenOfParents(
        parentIds: List<String>,
        includeValues: Boolean = true
    ): List<RemoteEntry> =
        parentIds.flatMap { listChildrenPaged(it, includeValues) }

    /**
     * Resolve entities by id (API Ids). Force inventory uses this after a structure-only
     * parent walk to load display names in batches — avoids IncludeValues×folder cold walk.
     */
    suspend fun getEntitiesByIds(ids: List<String>, includeValues: Boolean = true): List<RemoteEntry>

    suspend fun addFolder(parentId: String, name: String): String
    suspend fun addFile(parentId: String, name: String, bytes: ByteArray, mimeType: String): String
    suspend fun uploadFile(entityId: String, name: String, bytes: ByteArray, mimeType: String)
    suspend fun downloadFile(entityId: String): ByteArray?
    suspend fun fileSize(entityId: String): Long?
    suspend fun rename(entityId: String, newName: String)
    suspend fun move(entityId: String, oldParentId: String, newParentId: String)
    suspend fun delete(entityId: String)
}
