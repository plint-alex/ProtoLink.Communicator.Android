package ru.protolink.communicator.sync.engine

import ru.protolink.communicator.sync.model.LocalChange
import ru.protolink.communicator.sync.model.RemoteChange
import ru.protolink.communicator.sync.model.SyncItemMeta
import ru.protolink.communicator.sync.model.SyncMapping
import ru.protolink.communicator.sync.ports.LocalFileSystem
import ru.protolink.communicator.sync.ports.MetadataStore
import ru.protolink.communicator.sync.ports.RemoteCloud
import java.time.Instant

class SyncEngine(
    private val store: MetadataStore,
    private val fs: LocalFileSystem,
    private val remote: RemoteCloud,
    private val localClassifier: LocalChangeClassifier = LocalChangeClassifier(),
    private val remoteClassifier: RemoteChangeClassifier = RemoteChangeClassifier()
) {
    data class Result(
        val localChanges: Int,
        val remoteChanges: Int
    )

    suspend fun reconcileAll(
        mappings: List<SyncMapping>,
        compareSizeAndTime: Boolean = true
    ): Result {
        var localCount = 0
        var remoteCount = 0
        val dirtyByMapping = mutableMapOf<String, MutableSet<String>>()

        for (mapping in mappings) {
            if (store.getAll(mapping.id).isEmpty()) {
                bootstrapFromRemote(mapping)
            }
        }

        // Build remote trees once
        val remoteLocated = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        for (mapping in mappings) {
            walkRemote(mapping.cloudFolderId, mapping.id, "", remoteLocated)
        }

        val byId = mappings.associateBy { it.id }

        if (compareSizeAndTime) {
            // Enrich sizes (Content-Length) once, then Write/Read by size + time.
            val enrichedRemote = enrichRemoteSizes(remoteLocated)
            for (mapping in mappings) {
                val dirty = mutableSetOf<String>()
                val (writes, reads) = applyComparePhase(mapping, enrichedRemote, dirty)
                localCount += writes
                remoteCount += reads
                // Structural local changes only (adds / renames / removes).
                localCount += applyLocalPhase(mapping, dirty, skipSizeUpdates = true)
                dirtyByMapping[mapping.id] = dirty
            }
            for (mapping in mappings) {
                remoteCount += applyRemotePhase(
                    mapping,
                    byId,
                    enrichedRemote.filter { it.mappingId == mapping.id },
                    enrichedRemote,
                    dirtyByMapping[mapping.id] ?: emptySet()
                )
                store.setLastSyncUtc(mapping.id, Instant.now())
            }
        } else {
            // Classic path: local FS vs meta, then remote vs meta (no size/time compare phase).
            for (mapping in mappings) {
                val dirty = mutableSetOf<String>()
                localCount += applyLocalPhase(mapping, dirty, skipSizeUpdates = false)
                dirtyByMapping[mapping.id] = dirty
            }
            for (mapping in mappings) {
                remoteCount += applyRemotePhase(
                    mapping,
                    byId,
                    remoteLocated.filter { it.mappingId == mapping.id },
                    remoteLocated,
                    dirtyByMapping[mapping.id] ?: emptySet()
                )
                store.setLastSyncUtc(mapping.id, Instant.now())
            }
        }
        return Result(localCount, remoteCount)
    }

    /** Fill sizeBytes via header Content-Length once — not a full body download. */
    private suspend fun enrichRemoteSizes(
        located: List<RemoteChangeClassifier.RemoteLocated>
    ): List<RemoteChangeClassifier.RemoteLocated> {
        return located.map { loc ->
            if (loc.entry.isFolder || loc.entry.sizeBytes != null) loc
            else {
                val size = remote.fileSize(loc.entry.id)
                if (size != null) loc.copy(entry = loc.entry.copy(sizeBytes = size)) else loc
            }
        }
    }

    /**
     * For files present in meta + remote (+ local if any): compare size/time and Write or Read.
     * @return Pair(writeCount, readCount)
     */
    private suspend fun applyComparePhase(
        mapping: SyncMapping,
        allRemote: List<RemoteChangeClassifier.RemoteLocated>,
        dirty: MutableSet<String>
    ): Pair<Int, Int> {
        val snap = fs.snapshot(mapping.localRootPath)
            .associateBy { PathUtil.normalize(it.relativePath) }
        val remoteById = allRemote.associateBy { it.entry.id }
        var writes = 0
        var reads = 0

        for (meta in store.getAll(mapping.id)) {
            if (meta.isFolder) continue
            val located = remoteById[meta.remoteId] ?: continue
            if (located.mappingId != mapping.id) continue
            if (PathUtil.normalize(located.relativePath) != PathUtil.normalize(meta.relativePath)) continue

            val path = PathUtil.normalize(meta.relativePath)
            val localEntry = snap[path]
            val localSize = when {
                localEntry == null || !fs.exists(mapping.localRootPath, path) -> null
                else -> localEntry.sizeBytes
            }

            // Missing local while meta exists = user deleted (or rename). Let structural
            // local phase handle Removed/Renamed — do not re-download here.
            if (localSize == null) continue

            when (
                SyncDirectionDecide.decide(
                    localSize = localSize,
                    remoteSize = located.entry.sizeBytes,
                    metaSize = meta.sizeBytes,
                    remoteTime = located.entry.updateTime,
                    metaTime = meta.remoteUpdateTime
                )
            ) {
                SyncDirectionDecide.Action.Write -> {
                    if (localSize == null) continue
                    val name = PathUtil.nameOf(meta.relativePath)
                    val bytes = fs.readFile(mapping.localRootPath, meta.relativePath)
                    // Never push an empty stub over cloud content
                    if (bytes.isEmpty() && (located.entry.sizeBytes ?: 0L) > 0L) continue
                    remote.uploadFile(meta.remoteId, name, bytes, mimeOf(name))
                    store.upsert(
                        meta.copy(
                            sizeBytes = bytes.size.toLong(),
                            remoteUpdateTime = Instant.now()
                        )
                    )
                    dirty.add(path)
                    writes++
                }
                SyncDirectionDecide.Action.Read -> {
                    val bytes = remote.downloadFile(meta.remoteId) ?: continue
                    if (bytes.isEmpty() && localSize != null && localSize > 0L) continue
                    if (bytes.isEmpty() && localSize == null) continue // no empty placeholders
                    if (bytes.isNotEmpty() || localSize == 0L) {
                        fs.writeFile(mapping.localRootPath, meta.relativePath, bytes)
                        store.upsert(
                            meta.copy(
                                sizeBytes = bytes.size.toLong(),
                                remoteUpdateTime = located.entry.updateTime ?: Instant.now()
                            )
                        )
                        dirty.add(path)
                        reads++
                    }
                }
                SyncDirectionDecide.Action.Skip -> Unit
            }
        }
        return writes to reads
    }

    /** Seed store from remote and pull missing / empty local files. */
    private suspend fun bootstrapFromRemote(mapping: SyncMapping) {
        val located = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        walkRemote(mapping.cloudFolderId, mapping.id, "", located)
        val enriched = enrichRemoteSizes(located)
        for (loc in enriched.sortedBy { it.relativePath.count { c -> c == '/' } }) {
            if (loc.entry.isFolder) {
                if (!fs.exists(mapping.localRootPath, loc.relativePath)) {
                    fs.createDirectory(mapping.localRootPath, loc.relativePath)
                }
            } else {
                val existingSize = fs.snapshot(mapping.localRootPath)
                    .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(loc.relativePath) }
                    ?.sizeBytes
                val missing = !fs.exists(mapping.localRootPath, loc.relativePath)
                val action = SyncDirectionDecide.decide(
                    localSize = if (missing) null else existingSize,
                    remoteSize = loc.entry.sizeBytes,
                    metaSize = existingSize ?: 0L,
                    remoteTime = loc.entry.updateTime,
                    metaTime = null
                )
                if (action == SyncDirectionDecide.Action.Read || missing) {
                    val bytes = remote.downloadFile(loc.entry.id)
                    if (bytes != null && bytes.isNotEmpty()) {
                        fs.writeFile(mapping.localRootPath, loc.relativePath, bytes)
                    } else if (missing) {
                        // Do not create empty placeholders
                        continue
                    }
                }
            }
            val fsSize = fs.snapshot(mapping.localRootPath)
                .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(loc.relativePath) }
                ?.sizeBytes
                ?: loc.entry.sizeBytes
                ?: 0L
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = loc.entry.id,
                    parentRemoteId = loc.entry.parentId,
                    relativePath = loc.relativePath,
                    isFolder = loc.entry.isFolder,
                    sizeBytes = fsSize,
                    remoteUpdateTime = loc.entry.updateTime
                )
            )
        }
        recomputeFolderSizes(mapping)
    }

    private fun recomputeFolderSizes(mapping: SyncMapping) {
        val snap = fs.snapshot(mapping.localRootPath).associateBy { PathUtil.normalize(it.relativePath) }
        for (item in store.getAll(mapping.id).filter { it.isFolder }) {
            val fsSize = snap[PathUtil.normalize(item.relativePath)]?.sizeBytes ?: item.sizeBytes
            if (fsSize != item.sizeBytes) store.upsert(item.copy(sizeBytes = fsSize))
        }
    }

    private suspend fun applyLocalPhase(
        mapping: SyncMapping,
        dirty: MutableSet<String>,
        skipSizeUpdates: Boolean
    ): Int {
        val snap = fs.snapshot(mapping.localRootPath)
        val meta = store.getAll(mapping.id)
        val changes = localClassifier.classify(snap, meta)
        var applied = 0

        val adds = changes.filterIsInstance<LocalChange.Added>().sortedBy { it.entry.relativePath.count { c -> c == '/' } }
        val renames = changes.filterIsInstance<LocalChange.Renamed>()
        val updates = changes.filterIsInstance<LocalChange.Updated>()
        val removes = changes.filterIsInstance<LocalChange.Removed>()
            .sortedByDescending { it.meta.relativePath.count { c -> c == '/' } }

        for (r in renames) {
            val newParentPath = PathUtil.parentOf(r.newRelativePath)
            val newParentId = resolveParentId(mapping, newParentPath)
                ?: mapping.cloudFolderId
            if (newParentId != r.meta.parentRemoteId) {
                remote.move(r.meta.remoteId, r.meta.parentRemoteId, newParentId)
            }
            if (PathUtil.nameOf(r.meta.relativePath) != r.newName) {
                remote.rename(r.meta.remoteId, r.newName)
            }
            store.delete(mapping.id, r.meta.relativePath)
            store.upsert(
                r.meta.copy(
                    parentRemoteId = newParentId,
                    relativePath = r.newRelativePath
                )
            )
            dirty.add(r.newRelativePath)
            dirty.add(PathUtil.normalize(r.meta.relativePath))
            applied++
        }

        for (a in adds) {
            val parentPath = PathUtil.parentOf(a.entry.relativePath)
            val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
            val name = PathUtil.nameOf(a.entry.relativePath)
            val id: String
            if (a.entry.isFolder) {
                id = remote.addFolder(parentId, name)
            } else {
                val bytes = fs.readFile(mapping.localRootPath, a.entry.relativePath)
                id = remote.addFile(parentId, name, bytes, mimeOf(name))
            }
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = id,
                    parentRemoteId = parentId,
                    relativePath = PathUtil.normalize(a.entry.relativePath),
                    isFolder = a.entry.isFolder,
                    sizeBytes = a.entry.sizeBytes,
                    remoteUpdateTime = Instant.now()
                )
            )
            dirty.add(PathUtil.normalize(a.entry.relativePath))
            applied++
        }

        if (!skipSizeUpdates) {
            for (u in updates) {
                if (u.meta.isFolder) {
                    store.upsert(u.meta.copy(sizeBytes = u.newSize))
                } else {
                    val name = PathUtil.nameOf(u.meta.relativePath)
                    val bytes = fs.readFile(mapping.localRootPath, u.meta.relativePath)
                    remote.uploadFile(u.meta.remoteId, name, bytes, mimeOf(name))
                    store.upsert(u.meta.copy(sizeBytes = u.newSize, remoteUpdateTime = Instant.now()))
                }
                dirty.add(PathUtil.normalize(u.meta.relativePath))
                applied++
            }
        } else {
            // Folder size bookkeeping only
            for (u in updates.filter { it.meta.isFolder }) {
                store.upsert(u.meta.copy(sizeBytes = u.newSize))
                applied++
            }
        }

        for (rm in removes) {
            remote.delete(rm.meta.remoteId)
            store.delete(mapping.id, rm.meta.relativePath)
            dirty.add(PathUtil.normalize(rm.meta.relativePath))
            applied++
        }

        return applied
    }

    private suspend fun applyRemotePhase(
        mapping: SyncMapping,
        mappingsById: Map<String, SyncMapping>,
        @Suppress("UNUSED_PARAMETER") remoteInMapping: List<RemoteChangeClassifier.RemoteLocated>,
        allRemote: List<RemoteChangeClassifier.RemoteLocated>,
        dirty: Set<String>
    ): Int {
        val meta = store.getAll(mapping.id)
        val allIds = store.getAllRemoteIds()
        val cross = allRemote
        val changes = remoteClassifier.classify(meta, cross, allIds, dirty)

        var applied = 0
        for (c in changes) {
            when (c) {
                is RemoteChange.Renamed -> {
                    if (c.meta.mappingId != mapping.id) continue
                    val located = cross.find { it.entry.id == c.meta.remoteId }
                    val targetMappingId = located?.mappingId
                    if (targetMappingId != null && targetMappingId != mapping.id) {
                        val dest = mappingsById[targetMappingId] ?: continue
                        val bytes = when {
                            c.meta.isFolder -> null
                            fs.exists(mapping.localRootPath, c.meta.relativePath) ->
                                fs.readFile(mapping.localRootPath, c.meta.relativePath)
                            else -> remote.downloadFile(c.meta.remoteId)
                        }
                        if (fs.exists(mapping.localRootPath, c.meta.relativePath)) {
                            fs.delete(mapping.localRootPath, c.meta.relativePath, c.meta.isFolder)
                        }
                        store.delete(mapping.id, c.meta.relativePath)
                        val destPath = PathUtil.normalize(located!!.relativePath)
                        if (c.meta.isFolder) {
                            fs.createDirectory(dest.localRootPath, destPath)
                        } else if (bytes != null && bytes.isNotEmpty()) {
                            fs.writeFile(dest.localRootPath, destPath, bytes)
                        }
                        store.upsert(
                            c.meta.copy(
                                mappingId = targetMappingId,
                                relativePath = destPath,
                                parentRemoteId = located.entry.parentId,
                                sizeBytes = located.entry.sizeBytes ?: bytes?.size?.toLong() ?: c.meta.sizeBytes,
                                remoteUpdateTime = located.entry.updateTime
                            )
                        )
                    } else {
                        fs.move(mapping.localRootPath, c.meta.relativePath, c.newRelativePath, c.meta.isFolder)
                        store.delete(mapping.id, c.meta.relativePath)
                        store.upsert(
                            c.meta.copy(
                                relativePath = c.newRelativePath,
                                parentRemoteId = c.remote.parentId,
                                sizeBytes = c.remote.sizeBytes ?: c.meta.sizeBytes,
                                remoteUpdateTime = c.remote.updateTime
                            )
                        )
                    }
                    applied++
                }
                is RemoteChange.Added -> {
                    val located = cross.find { it.entry.id == c.remote.id } ?: continue
                    if (located.mappingId != mapping.id) continue
                    if (c.remote.isFolder) {
                        fs.createDirectory(mapping.localRootPath, c.relativePath)
                        store.upsert(
                            SyncItemMeta(
                                mappingId = mapping.id,
                                remoteId = c.remote.id,
                                parentRemoteId = c.remote.parentId,
                                relativePath = c.relativePath,
                                isFolder = true,
                                sizeBytes = 0,
                                remoteUpdateTime = c.remote.updateTime
                            )
                        )
                    } else {
                        val bytes = remote.downloadFile(c.remote.id) ?: continue
                        if (bytes.isEmpty()) continue
                        fs.writeFile(mapping.localRootPath, c.relativePath, bytes)
                        store.upsert(
                            SyncItemMeta(
                                mappingId = mapping.id,
                                remoteId = c.remote.id,
                                parentRemoteId = c.remote.parentId,
                                relativePath = c.relativePath,
                                isFolder = false,
                                sizeBytes = bytes.size.toLong(),
                                remoteUpdateTime = c.remote.updateTime
                            )
                        )
                    }
                    applied++
                }
                is RemoteChange.Removed -> {
                    if (c.meta.mappingId != mapping.id) continue
                    if (cross.any { it.entry.id == c.meta.remoteId }) continue
                    if (fs.exists(mapping.localRootPath, c.meta.relativePath)) {
                        fs.delete(mapping.localRootPath, c.meta.relativePath, c.meta.isFolder)
                    }
                    store.delete(mapping.id, c.meta.relativePath)
                    applied++
                }
                is RemoteChange.Updated -> {
                    // Size/time content sync already handled in applyComparePhase.
                    // Keep as safety net only when local still matches meta (unchanged locally).
                    if (c.meta.mappingId != mapping.id) continue
                    if (c.meta.isFolder) continue
                    if (dirty.contains(PathUtil.normalize(c.meta.relativePath))) continue
                    val snap = fs.snapshot(mapping.localRootPath)
                        .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(c.meta.relativePath) }
                    if (snap != null && snap.sizeBytes != c.meta.sizeBytes) continue
                    val bytes = remote.downloadFile(c.remote.id) ?: continue
                    if (bytes.isEmpty()) continue
                    fs.writeFile(mapping.localRootPath, c.meta.relativePath, bytes)
                    store.upsert(
                        c.meta.copy(
                            sizeBytes = bytes.size.toLong(),
                            remoteUpdateTime = c.remote.updateTime ?: Instant.now()
                        )
                    )
                    applied++
                }
            }
        }
        return applied
    }

    private suspend fun walkRemote(
        folderId: String,
        mappingId: String,
        relativeParent: String,
        out: MutableList<RemoteChangeClassifier.RemoteLocated>
    ) {
        val children = remote.listChildrenPaged(folderId)
        for (child in children) {
            val rel = PathUtil.join(relativeParent, child.name)
            out.add(
                RemoteChangeClassifier.RemoteLocated(
                    entry = child.copy(parentId = folderId),
                    mappingId = mappingId,
                    relativePath = rel
                )
            )
            if (child.isFolder) {
                walkRemote(child.id, mappingId, rel, out)
            }
        }
    }

    private fun resolveParentId(mapping: SyncMapping, parentPath: String): String? {
        if (parentPath.isEmpty()) return mapping.cloudFolderId
        val existing = store.getAll(mapping.id)
            .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(parentPath) }
        return existing?.remoteId
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html", true) -> "text/html"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }
}
