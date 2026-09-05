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
        mappings: List<SyncMapping>
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

        // Always hash-compare every mapped file so all devices converge to the same bytes.
        val enrichedRemote = remoteLocated
        for (mapping in mappings) {
            val dirty = mutableSetOf<String>()
            val (writes, reads) = applyComparePhase(mapping, enrichedRemote, dirty)
            localCount += writes
            remoteCount += reads
            val remoteForMapping = enrichedRemote.filter { it.mappingId == mapping.id }
            localCount += applyLocalPhase(mapping, dirty, skipSizeUpdates = true, remoteForMapping)
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
        return Result(localCount, remoteCount)
    }

    /**
     * Upload local FS changes vs meta only (no remote walk / download).
     * Returns number of local ops applied (uploads / creates / deletes / renames).
     */
    suspend fun pushLocalChanges(mappings: List<SyncMapping>): Int {
        var applied = 0
        for (mapping in mappings) {
            val dirty = mutableSetOf<String>()
            applied += applyLocalPhase(mapping, dirty, skipSizeUpdates = false, remoteInMapping = emptyList())
            store.setLastSyncUtc(mapping.id, Instant.now())
        }
        return applied
    }

    /**
     * Formerly filled sizeBytes via Content-Length. OpenResty serves getFile chunked
     * (no Content-Length); probing every file stalled mapped sync. Return as-is —
     * Decide uses UpdateTime + ContentHash instead.
     */
    private suspend fun enrichRemoteSizes(
        located: List<RemoteChangeClassifier.RemoteLocated>
    ): List<RemoteChangeClassifier.RemoteLocated> = located

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

            // Empty / tiny stubs: prefer remote when cloud likely has real content.
            if (localSize == 0L || (localSize < 64L && (located.entry.sizeBytes ?: 0L) > localSize)) {
                val bytes = remote.downloadFile(meta.remoteId)
                if (bytes != null && bytes.isNotEmpty()) {
                    fs.writeFile(mapping.localRootPath, meta.relativePath, bytes)
                    store.upsert(
                        meta.copy(
                            sizeBytes = bytes.size.toLong(),
                            contentHash = ContentHashUtil.sha256Hex(bytes),
                            remoteUpdateTime = located.entry.updateTime ?: Instant.now()
                        )
                    )
                    dirty.add(path)
                    reads++
                }
                continue
            }

            val localBytes = fs.readFile(mapping.localRootPath, path)
            val localHash = ContentHashUtil.sha256Hex(localBytes)

            // Always download remote bytes and compare hashes. Never forge remoteHash from
            // meta or upload when remote content is unknown.
            val remoteBytes = remote.downloadFile(meta.remoteId)
            if (remoteBytes == null || remoteBytes.isEmpty()) {
                throw SyncException(
                    "Remote download failed or returned empty; cannot reconcile file.",
                    relativePath = path,
                    localHash = localHash
                )
            }

            val remoteHash = ContentHashUtil.sha256Hex(remoteBytes)
            when (
                ContentHashUtil.decideByHash(
                    localHash,
                    remoteHash,
                    meta.contentHash,
                    located.entry.updateTime,
                    meta.remoteUpdateTime
                )
            ) {
                SyncDirectionDecide.Action.Write -> {
                    val name = PathUtil.nameOf(meta.relativePath)
                    if (localBytes.isEmpty() && (located.entry.sizeBytes ?: 0L) > 0L) {
                        throw SyncException(
                            "Refusing to upload empty local file over non-empty remote.",
                            relativePath = path,
                            localHash = localHash,
                            remoteHash = remoteHash
                        )
                    }
                    remote.uploadFile(meta.remoteId, name, localBytes, mimeOf(name))
                    store.upsert(
                        meta.copy(
                            sizeBytes = localBytes.size.toLong(),
                            contentHash = localHash,
                            remoteUpdateTime = Instant.now()
                        )
                    )
                    dirty.add(path)
                    writes++
                }
                SyncDirectionDecide.Action.Read -> {
                    fs.writeFile(mapping.localRootPath, meta.relativePath, remoteBytes)
                    store.upsert(
                        meta.copy(
                            sizeBytes = remoteBytes.size.toLong(),
                            contentHash = remoteHash,
                            remoteUpdateTime = located.entry.updateTime ?: Instant.now()
                        )
                    )
                    dirty.add(path)
                    reads++
                }
                SyncDirectionDecide.Action.Skip -> {
                    store.upsert(
                        meta.copy(
                            contentHash = localHash,
                            remoteUpdateTime = located.entry.updateTime ?: meta.remoteUpdateTime
                        )
                    )
                }
                SyncDirectionDecide.Action.Conflict -> {
                    throw SyncConflictException(
                        path,
                        localHash,
                        remoteHash,
                        meta.contentHash,
                        "Local and remote both differ from sync baseline (or baseline is empty). Use Force Upload or Force Download."
                    )
                }
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
                val missing = !fs.exists(mapping.localRootPath, loc.relativePath)
                // Bootstrap has no meta baseline — prefer cloud content so devices share one tree.
                val remoteBytes = remote.downloadFile(loc.entry.id)
                if (remoteBytes != null && remoteBytes.isNotEmpty()) {
                    if (missing) {
                        fs.writeFile(mapping.localRootPath, loc.relativePath, remoteBytes)
                    } else {
                        val localHash = ContentHashUtil.sha256Hex(
                            fs.readFile(mapping.localRootPath, loc.relativePath)
                        )
                        val remoteHash = ContentHashUtil.sha256Hex(remoteBytes)
                        if (!localHash.equals(remoteHash, ignoreCase = true)) {
                            fs.writeFile(mapping.localRootPath, loc.relativePath, remoteBytes)
                        }
                    }
                } else if (missing) {
                    continue
                }
            }
            val fsSnap = fs.snapshot(mapping.localRootPath)
                .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(loc.relativePath) }
            val fsSize = fsSnap?.sizeBytes
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
                    contentHash = if (loc.entry.isFolder) "" else (fsSnap?.contentHash ?: ""),
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
        skipSizeUpdates: Boolean,
        remoteInMapping: List<RemoteChangeClassifier.RemoteLocated> = emptyList()
    ): Int {
        val snap = fs.snapshot(mapping.localRootPath)
        val meta = store.getAll(mapping.id)
        val changes = localClassifier.classify(snap, meta)
        var applied = 0
        val remoteByPath = remoteInMapping.associateBy { PathUtil.normalize(it.relativePath) }

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
            val path = PathUtil.normalize(a.entry.relativePath)
            val existingRemote = remoteByPath[path]
            // Meta incomplete but cloud already has this path: adopt id (and upload local edits).
            // Creating a duplicate aborts sync; skipping entirely never uploads Android Saves.
            if (existingRemote != null) {
                val name = PathUtil.nameOf(a.entry.relativePath)
                if (a.entry.isFolder) {
                    store.upsert(
                        SyncItemMeta(
                            mappingId = mapping.id,
                            remoteId = existingRemote.entry.id,
                            parentRemoteId = existingRemote.entry.parentId,
                            relativePath = path,
                            isFolder = true,
                            sizeBytes = a.entry.sizeBytes,
                            remoteUpdateTime = existingRemote.entry.updateTime
                        )
                    )
                } else {
                    val bytes = fs.readFile(mapping.localRootPath, a.entry.relativePath)
                    val remoteSize = existingRemote.entry.sizeBytes
                    val localLooksStub =
                        bytes.isEmpty() || (bytes.size < 64 && remoteSize != null && remoteSize > bytes.size)
                    if (localLooksStub) {
                        val cloud = remote.downloadFile(existingRemote.entry.id)
                        if (cloud != null && cloud.isNotEmpty()) {
                            fs.writeFile(mapping.localRootPath, a.entry.relativePath, cloud)
                            store.upsert(
                                SyncItemMeta(
                                    mappingId = mapping.id,
                                    remoteId = existingRemote.entry.id,
                                    parentRemoteId = existingRemote.entry.parentId,
                                    relativePath = path,
                                    isFolder = false,
                                    sizeBytes = cloud.size.toLong(),
                                    contentHash = ContentHashUtil.sha256Hex(cloud),
                                    remoteUpdateTime = existingRemote.entry.updateTime ?: Instant.now()
                                )
                            )
                            dirty.add(path)
                            applied++
                        }
                        continue
                    }
                    // Adopt existing remote id: converge by content hash (never upload because size unknown).
                    val cloud = remote.downloadFile(existingRemote.entry.id)
                        ?: throw SyncException(
                            "Remote download failed while adopting local add.",
                            relativePath = path
                        )
                    if (cloud.isEmpty()) {
                        throw SyncException(
                            "Remote download returned empty while adopting local add.",
                            relativePath = path
                        )
                    }
                    val localHash = ContentHashUtil.sha256Hex(bytes)
                    val remoteHash = ContentHashUtil.sha256Hex(cloud)
                    when (
                        ContentHashUtil.decideByHash(
                            localHash, remoteHash, metaHash = null,
                            existingRemote.entry.updateTime, metaUpdateTime = null
                        )
                    ) {
                        SyncDirectionDecide.Action.Write -> {
                            remote.uploadFile(existingRemote.entry.id, name, bytes, mimeOf(name))
                            store.upsert(
                                SyncItemMeta(
                                    mappingId = mapping.id,
                                    remoteId = existingRemote.entry.id,
                                    parentRemoteId = existingRemote.entry.parentId,
                                    relativePath = path,
                                    isFolder = false,
                                    sizeBytes = bytes.size.toLong(),
                                    contentHash = localHash,
                                    remoteUpdateTime = Instant.now()
                                )
                            )
                        }
                        SyncDirectionDecide.Action.Read, SyncDirectionDecide.Action.Skip -> {
                            if (!localHash.equals(remoteHash, ignoreCase = true)) {
                                fs.writeFile(mapping.localRootPath, a.entry.relativePath, cloud)
                            }
                            store.upsert(
                                SyncItemMeta(
                                    mappingId = mapping.id,
                                    remoteId = existingRemote.entry.id,
                                    parentRemoteId = existingRemote.entry.parentId,
                                    relativePath = path,
                                    isFolder = false,
                                    sizeBytes = cloud.size.toLong(),
                                    contentHash = remoteHash,
                                    remoteUpdateTime = existingRemote.entry.updateTime ?: Instant.now()
                                )
                            )
                        }
                        SyncDirectionDecide.Action.Conflict -> {
                            throw SyncConflictException(
                                path,
                                localHash,
                                remoteHash,
                                null,
                                "Local add collides with different remote content. Use Force Upload or Force Download."
                            )
                        }
                    }
                }
                dirty.add(path)
                applied++
                continue
            }
            val parentPath = PathUtil.parentOf(a.entry.relativePath)
            val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
            val name = PathUtil.nameOf(a.entry.relativePath)
            val id: String
            if (a.entry.isFolder) {
                id = remote.addFolder(parentId, name)
            } else {
                val bytes = fs.readFile(mapping.localRootPath, a.entry.relativePath)
                // Never push an empty / editor stub when we are about to adopt cloud content.
                if (bytes.isEmpty()) continue
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
                    contentHash = if (a.entry.isFolder) "" else a.entry.contentHash.ifEmpty {
                        ContentHashUtil.sha256Hex(fs.readFile(mapping.localRootPath, a.entry.relativePath))
                    },
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
                    store.upsert(
                        u.meta.copy(
                            sizeBytes = u.newSize,
                            contentHash = ContentHashUtil.sha256Hex(bytes),
                            remoteUpdateTime = Instant.now()
                        )
                    )
                }
                dirty.add(PathUtil.normalize(u.meta.relativePath))
                applied++
            }
        } else {
            // Folder size bookkeeping + same-size content uploads (hash-driven).
            for (u in updates.filter { it.meta.isFolder }) {
                store.upsert(u.meta.copy(sizeBytes = u.newSize))
                applied++
            }
            for (u in updates.filter { !it.meta.isFolder }) {
                // Content convergence is owned by applyComparePhase (always hash-verify).
                // Do not upload here — that overwrote unknown cloud content when download failed.
                store.upsert(u.meta.copy(sizeBytes = u.newSize))
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
                                contentHash = ContentHashUtil.sha256Hex(bytes),
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
                            contentHash = ContentHashUtil.sha256Hex(bytes),
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

    /** Overwrite server with all local files; meta follows local. Returns files uploaded. */
    suspend fun forcePushMapping(mapping: SyncMapping): Int {
        val snap = fs.snapshot(mapping.localRootPath)
            .sortedWith(compareBy({ it.relativePath.count { c -> c == '/' } }, { if (it.isFolder) 0 else 1 }))
        if (snap.isEmpty()) {
            throw SyncException(
                "Force upload found no local files. Check the mapped folder SAF permission."
            )
        }
        var files = 0
        for (entry in snap) {
            val path = PathUtil.normalize(entry.relativePath)
            val parentPath = PathUtil.parentOf(path)
            val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
            val name = PathUtil.nameOf(path)
            val existing = store.getAll(mapping.id)
                .firstOrNull { PathUtil.normalize(it.relativePath) == path }
            if (entry.isFolder) {
                if (existing != null) continue
                val id = remote.addFolder(parentId, name)
                store.upsert(
                    SyncItemMeta(
                        mappingId = mapping.id,
                        remoteId = id,
                        parentRemoteId = parentId,
                        relativePath = path,
                        isFolder = true,
                        sizeBytes = 0,
                        contentHash = "",
                        remoteUpdateTime = Instant.now()
                    )
                )
                continue
            }
            val bytes = fs.readFile(mapping.localRootPath, path)
            val hash = ContentHashUtil.sha256Hex(bytes)
            val remoteId = if (existing != null) {
                remote.uploadFile(existing.remoteId, name, bytes, mimeOf(name))
                existing.remoteId
            } else {
                remote.addFile(parentId, name, bytes, mimeOf(name))
            }
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = remoteId,
                    parentRemoteId = parentId,
                    relativePath = path,
                    isFolder = false,
                    sizeBytes = bytes.size.toLong(),
                    contentHash = hash,
                    remoteUpdateTime = Instant.now()
                )
            )
            files++
        }
        if (files == 0) {
            throw SyncException("Force upload processed 0 files (folders only or empty tree).")
        }
        store.setLastSyncUtc(mapping.id, Instant.now())
        return files
    }

    /** Overwrite local FS with all remote files; meta follows remote. Returns files downloaded. */
    suspend fun forcePullMapping(mapping: SyncMapping): Int {
        val located = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        walkRemote(mapping.cloudFolderId, mapping.id, "", located)
        if (located.isEmpty()) {
            throw SyncException("Force download found no remote files in this folder.")
        }
        var files = 0
        for (loc in located.sortedBy { it.relativePath.count { c -> c == '/' } }) {
            val path = PathUtil.normalize(loc.relativePath)
            if (loc.entry.isFolder) {
                if (!fs.exists(mapping.localRootPath, path)) {
                    fs.createDirectory(mapping.localRootPath, path)
                }
                store.upsert(
                    SyncItemMeta(
                        mappingId = mapping.id,
                        remoteId = loc.entry.id,
                        parentRemoteId = loc.entry.parentId,
                        relativePath = path,
                        isFolder = true,
                        sizeBytes = 0,
                        contentHash = "",
                        remoteUpdateTime = loc.entry.updateTime ?: Instant.now()
                    )
                )
                continue
            }
            val bytes = remote.downloadFile(loc.entry.id)
                ?: throw SyncException("Force download failed: remote file missing.", relativePath = path)
            if (bytes.isEmpty()) {
                throw SyncException("Force download returned empty file.", relativePath = path)
            }
            fs.writeFile(mapping.localRootPath, path, bytes)
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = loc.entry.id,
                    parentRemoteId = loc.entry.parentId,
                    relativePath = path,
                    isFolder = false,
                    sizeBytes = bytes.size.toLong(),
                    contentHash = ContentHashUtil.sha256Hex(bytes),
                    remoteUpdateTime = loc.entry.updateTime ?: Instant.now()
                )
            )
            files++
        }
        if (files == 0) {
            throw SyncException("Force download processed 0 files (folders only).")
        }
        store.setLastSyncUtc(mapping.id, Instant.now())
        return files
    }
}
