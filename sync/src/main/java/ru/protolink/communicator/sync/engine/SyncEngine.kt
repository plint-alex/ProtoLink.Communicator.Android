package ru.protolink.communicator.sync.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.LocalChange
import ru.protolink.communicator.sync.model.RemoteChange
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.model.SyncItemMeta
import ru.protolink.communicator.sync.model.SyncMapping
import ru.protolink.communicator.sync.ports.LocalFileSystem
import ru.protolink.communicator.sync.ports.MetadataStore
import ru.protolink.communicator.sync.ports.RemoteCloud
import java.time.Instant
import kotlin.coroutines.coroutineContext

class SyncEngine(
    private val store: MetadataStore,
    private val fs: LocalFileSystem,
    private val remote: RemoteCloud,
    private val localClassifier: LocalChangeClassifier = LocalChangeClassifier(),
    private val remoteClassifier: RemoteChangeClassifier = RemoteChangeClassifier()
) {
    companion object {
        /**
         * Per-item getFile budget (Win RemoteDownloadBudget 45s). One hung download must not
         * keep ApplyCompare / ApplyRemote open past the daily-use LastSync window.
         */
        var remoteDownloadBudgetMs: Long = 45_000L

        /**
         * Parallel GetEntities for folder walk. Align with Windows
         * GetEntitiesThrottlingHandler.SyncLaneConcurrency (24). Must pair with OkHttp
         * maxRequestsPerHost ≥ this (default 5 capped live Force to ~140–180 GE/min).
         */
        const val WALK_REMOTE_CONCURRENCY = 24

        /**
         * Ids IncludeValues batch size for Force name resolve.
         * Live 1.0.53: concurrent batches of 200 hit HTTP 504 Gateway Time-out (~90s).
         * 1.0.54 used 25 (safe but too slow — mid-name GE past 28m). 50 stays under the
         * gateway budget while cutting name-pass wall ~2× vs 25.
         */
        const val FORCE_NAME_BATCH = 50

        /**
         * Parallel Ids IncludeValues name resolve. Keep below [WALK_REMOTE_CONCURRENCY]:
         * 24×200 Values smashed Api/gateway (ForceIdsNames 504 class). 1.0.54 used 2 and
         * never reached downloadFile on ~15–20k edges (~10 ids/s). 8 × batch 50 ≈80 ids/s
         * keeps half-split on 5xx (NFR-SYNC-1).
         */
        const val FORCE_NAME_CONCURRENCY = 8

        /**
         * Force structure inventory: parents per GetEntities ParentIdsMatchAny call.
         * Cuts GE count ~batch vs 1/folder (live ~3.5k×RTT was still mid-walk at 30m).
         */
        const val FORCE_PARENT_BATCH = 48

        /**
         * Parallel getFile / mkdir materialize within a Force wave. Live 1.0.56 was sequential
         * (~12 files/min with SAF listChildren gaps) and never reached Force-complete in ~25m.
         * Align with name concurrency; OkHttp maxRequestsPerHost already ≥ walk (24).
         */
        const val FORCE_DOWNLOAD_CONCURRENCY = 8

        /** Force inventory GetEntities attempts (transient HTTP 5xx). */
        const val FORCE_INVENTORY_MAX_ATTEMPTS = SyncInventoryFetch.MAX_ATTEMPTS

        /**
         * Lower = materialize / deepen earlier (AC fill folders before wide sibling trees).
         * Any path segment matches — live `protolink.ru/agents/…` must not stay prio-2 behind
         * hundreds of mapping-root screenshots.
         */
        fun forcePathPriority(relativePath: String): Int {
            val parts = PathUtil.normalize(relativePath).lowercase()
                .split('/')
                .filter { it.isNotEmpty() }
            if (parts.any { it == "films" || it == "agentteamverify" }) return 0
            if (parts.any { it == "agents" || it.contains("test") }) return 1
            return 2
        }

        /**
         * Live 1.0.62 FAIL: Force getFile'd the entire MyNotesFolder (protolink.ru/agents→741+
         * and still incomplete at ~24m). Daily-use Force must finish: always download AC (prio 0);
         * elsewhere only Notes leaf pages (index.html) — structure+register the rest (binaries,
         * evidence dumps) so Force-complete + prune land without unbounded blob getFile.
         */
        fun forceShouldDownloadFile(relativePath: String): Boolean {
            if (forcePathPriority(relativePath) == 0) return true
            val name = PathUtil.nameOf(relativePath).lowercase()
            return name == "index.html" || name == "index.htm"
        }
    }
    data class Result(
        val localChanges: Int,
        val remoteChanges: Int
    )

    suspend fun reconcileAll(
        mappings: List<SyncMapping>
    ): Result {
        var localCount = 0
        var remoteCount = 0

        for (mapping in mappings) {
            coroutineContext.ensureActive()
            val meta = store.getAll(mapping.id)
            // Empty store OR folders-only (partial bootstrap died after mkdir) → pull remote files.
            if (meta.isEmpty() || meta.none { !it.isFolder }) {
                bootstrapFromRemote(mapping)
            }
        }

        // Build remote trees once. Progressive Walk materializes Api-only kids as soon as a
        // folder listing discovers them (Force wave parity) so phone GE×~1k folders cannot
        // starve ApplyRemote past the Sync wait (1.0.118 DOWNLOAD_API_ONLY_NOT_ON_SAF late SAF).
        val remoteLocated = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        val progressiveDirty = mutableSetOf<String>()
        for (mapping in mappings) {
            coroutineContext.ensureActive()
            walkRemote(mapping, "", remoteLocated, progressiveDirty = progressiveDirty)
        }

        val byId = mappings.associateBy { it.id }

        // Win ReconcileAll: per-mapping Compare→Remote→Local→LastSync (do not wait for sibling
        // maps' Apply* before stamping). Cloud materialize before local upload (cloud-wins).
        val enrichedRemote = remoteLocated
        for (mapping in mappings) {
            coroutineContext.ensureActive()
            // Seed dirty with Walk-progressive paths so SAF snapshot lag cannot Removed→DeleteEntity
            // the same-pass download (ApplyRemote Added dirty guard parity).
            val dirty = progressiveDirty
                .filter { norm ->
                    enrichedRemote.any {
                        it.mappingId == mapping.id && PathUtil.normalize(it.relativePath) == norm
                    }
                }
                .toMutableSet()
            val (writes, reads) = applyComparePhase(mapping, enrichedRemote, dirty)
            localCount += writes
            remoteCount += reads
            remoteCount += applyRemotePhase(
                mapping,
                byId,
                enrichedRemote.filter { it.mappingId == mapping.id },
                enrichedRemote,
                dirty
            )
            val remoteForMapping = enrichedRemote.filter { it.mappingId == mapping.id }
            // Sync Walk stops at Agents folder — ApplyLocal must skip the whole Agents tree
            // (not only queues) or Agents/docs looks local-Added and re-uploads forever
            // (companion to ApplyRemote isUnderAgentsTree Removed protect; 1.0.115 FAIL).
            localCount += applyLocalPhase(
                mapping,
                dirty,
                skipSizeUpdates = true,
                remoteForMapping,
                excludeEntireAgentsTree = true
            )
            store.setLastSyncUtc(mapping.id, Instant.now())
        }
        return Result(localCount, remoteCount)
    }

    /**
     * Upload local FS changes vs meta only (no remote walk / download).
     * Mappings with empty metadata are fully reconciled first (never blind-create remote entities).
     */
    suspend fun pushLocalChanges(mappings: List<SyncMapping>): Int {
        // Empty OR folders-only (partial mkdir bootstrap) → full reconcile before push.
        val needFull = mappings.filter {
            val meta = store.getAll(it.id)
            meta.isEmpty() || meta.none { m -> !m.isFolder }
        }
        val ready = mappings.filter {
            val meta = store.getAll(it.id)
            meta.isNotEmpty() && meta.any { m -> !m.isFolder }
        }
        if (needFull.isNotEmpty()) {
            reconcileAll(needFull)
        }
        var applied = 0
        for (mapping in ready) {
            coroutineContext.ensureActive()
            val dirty = mutableSetOf<String>()
            // Win PushLocalChanges: excludeAgentsQueues so Autopush settles under mapped
            // MyNotesFolder while AgentTeam rewrites task HTML (1.0.118 churn class).
            applied += applyLocalPhase(
                mapping,
                dirty,
                skipSizeUpdates = false,
                remoteInMapping = emptyList(),
                excludeAgentsQueues = true
            )
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
        // Win ApplyCompare: do not SnapshotFs here — per-meta Exists + Length only.
        // Full-tree snapshot on ~10k MyNotesFolder starved ApplyRemote (Api-only download miss).
        val remoteById = allRemote.associateBy { it.entry.id }
        var writes = 0
        var reads = 0

        for (meta in store.getAll(mapping.id)) {
            // Force SyncFlight cancel must abort mid-phase (live 1.0.64: phase-boundary-only
            // ensureActive left mutex held >120s on MyNotesFolder → FORCE_SYNC_LOCK_TIMEOUT).
            coroutineContext.ensureActive()
            if (meta.isFolder) continue
            val path = PathUtil.normalize(meta.relativePath)
            // Sync Walk stops at Agents — skip getFile for any Agents path (queues + docs).
            if (AgentsRoleQueuePaths.isUnderAgentsTree(path)) continue
            val located = remoteById[meta.remoteId] ?: continue
            if (located.mappingId != mapping.id) continue
            if (PathUtil.normalize(located.relativePath) != path) continue

            val localSize = fs.fileLengthBytes(mapping.localRootPath, path)

            // Missing local while meta exists = user deleted (or rename). Let structural
            // local phase handle Removed/Renamed — do not re-download here.
            if (localSize == null) continue

            // Empty / tiny stubs: prefer remote when cloud likely has real content.
            if (localSize == 0L || (localSize < 64L && (located.entry.sizeBytes ?: 0L) > localSize)) {
                val bytes = remote.downloadFile(meta.remoteId)
                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        fs.writeFile(mapping.localRootPath, meta.relativePath, bytes)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (SyncMissingLocalPath.isMissing(e)) {
                            SyncMissingLocalPath.log("ApplyComparePhase", path, path, e)
                            continue
                        }
                        throw e
                    }
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

            // Size+baseline+listing fast path: avoid SHA-256 of every unchanged file (Win 1.0.75).
            if (ContentHashUtil.canSkipCompareWithoutHash(
                    localSize,
                    meta.sizeBytes,
                    meta.contentHash,
                    located.entry.updateTime,
                    meta.remoteUpdateTime
                )
            ) {
                continue
            }

            val localHash = ContentHashUtil.trySha256Hex {
                fs.readFile(mapping.localRootPath, path)
            }
            if (localHash == null) {
                SyncMissingLocalPath.log("ApplyComparePhase", path, path)
                continue
            }

            // Unchanged local + remote listing not newer → skip getFile (Win CanSkipRemoteContentDownload).
            if (ContentHashUtil.canSkipRemoteContentDownload(
                    localHash,
                    meta.contentHash,
                    located.entry.updateTime,
                    meta.remoteUpdateTime
                )
            ) {
                continue
            }

            // Always download remote bytes and compare hashes. Never forge remoteHash from
            // meta or upload when remote content is unknown.
            // null = failed/not-found → soft-fail this item; isEmpty() is legitimate empty content.
            val remoteBytes = remote.downloadFile(meta.remoteId)
            if (remoteBytes == null) {
                SyncRemoteDownload.logFailed("ApplyComparePhase", path, meta.remoteId)
                continue
            }

            val remoteHash = ContentHashUtil.sha256Hex(remoteBytes)
            try {
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
                        val localBytes = fs.readFile(mapping.localRootPath, path)
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
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (SyncMissingLocalPath.isMissing(e)) {
                    SyncMissingLocalPath.log("ApplyComparePhase", path, path, e)
                } else {
                    throw e
                }
            }
        }
        return writes to reads
    }

    /**
     * Seed store from remote and pull missing / empty local files.
     * Per-item size/hash must NOT call [LocalFileSystem.snapshot] (full-tree walk + hash) —
     * that is O(n²) on large/polluted Notes roots and dies after mkdir-only stubs (Win hashes
     * only the written file). Match Windows BootstrapFromRemoteAsync.
     */
    private suspend fun bootstrapFromRemote(mapping: SyncMapping) {
        val located = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        // Cold bootstrap: progressive materialize during Walk would race mkdir+getFile with
        // the bootstrap body below — inventory only here (includeValues via empty meta).
        walkRemote(mapping, "", located, progressiveMaterialize = false)
        val enriched = enrichRemoteSizes(located)
        for (loc in enriched.sortedBy { it.relativePath.count { c -> c == '/' } }) {
            coroutineContext.ensureActive()
            var fileBytes: ByteArray? = null
            try {
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
                            fileBytes = remoteBytes
                        } else {
                            val localBytes = fs.readFile(mapping.localRootPath, loc.relativePath)
                            val localHash = ContentHashUtil.sha256Hex(localBytes)
                            val remoteHash = ContentHashUtil.sha256Hex(remoteBytes)
                            if (!localHash.equals(remoteHash, ignoreCase = true)) {
                                fs.writeFile(mapping.localRootPath, loc.relativePath, remoteBytes)
                                fileBytes = remoteBytes
                            } else {
                                fileBytes = localBytes
                            }
                        }
                    } else if (missing) {
                        if (remoteBytes == null) {
                            SyncRemoteDownload.logFailed(
                                "BootstrapFromRemote", loc.relativePath, loc.entry.id
                            )
                        }
                        continue
                    } else {
                        fileBytes = runCatching {
                            fs.readFile(mapping.localRootPath, loc.relativePath)
                        }.getOrNull()
                        if (fileBytes == null) continue
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "BootstrapFromRemote",
                    loc.relativePath,
                    loc.entry.id,
                    e.message ?: e.javaClass.simpleName
                )
                continue
            }
            val fsSize = when {
                loc.entry.isFolder -> 0L
                fileBytes != null -> fileBytes.size.toLong()
                else -> loc.entry.sizeBytes ?: 0L
            }
            val hash = if (loc.entry.isFolder || fileBytes == null) {
                ""
            } else {
                ContentHashUtil.sha256Hex(fileBytes)
            }
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = loc.entry.id,
                    parentRemoteId = loc.entry.parentId,
                    relativePath = loc.relativePath,
                    isFolder = loc.entry.isFolder,
                    sizeBytes = fsSize,
                    contentHash = hash,
                    remoteUpdateTime = loc.entry.updateTime
                )
            )
        }
        recomputeFolderSizes(mapping)
    }

    private fun recomputeFolderSizes(mapping: SyncMapping) {
        // Size rollup only — Win SnapshotFs(computeContentHashes: false) for folder sizes.
        val snap = fs.snapshot(mapping.localRootPath, computeContentHashes = false)
            .associateBy { PathUtil.normalize(it.relativePath) }
        for (item in store.getAll(mapping.id).filter { it.isFolder }) {
            val fsSize = snap[PathUtil.normalize(item.relativePath)]?.sizeBytes ?: item.sizeBytes
            if (fsSize != item.sizeBytes) store.upsert(item.copy(sizeBytes = fsSize))
        }
    }

    private suspend fun applyLocalPhase(
        mapping: SyncMapping,
        dirty: MutableSet<String>,
        skipSizeUpdates: Boolean,
        remoteInMapping: List<RemoteChangeClassifier.RemoteLocated> = emptyList(),
        excludeAgentsQueues: Boolean = false,
        excludeEntireAgentsTree: Boolean = false
    ): Int {
        // Win ApplyLocal: reconcile → size/structure only; push → hash for same-size Updated.
        // AutoPush: queue-only skip (Win 1.0.118). Sync+Walk Agents stop: whole Agents tree.
        val skipPath: ((String) -> Boolean)? = when {
            excludeEntireAgentsTree -> AgentsRoleQueuePaths::isUnderAgentsTree
            excludeAgentsQueues -> AgentsRoleQueuePaths::shouldSkipLocalPush
            else -> null
        }
        val snap = fs.snapshot(
            mapping.localRootPath,
            computeContentHashes = !skipSizeUpdates,
            skipRelativePath = skipPath
        )
        // Pair snap+meta filters so skipped Agents paths do not classify as Removed.
        val meta = store.getAll(mapping.id).let { all ->
            when {
                excludeEntireAgentsTree ->
                    all.filter { !AgentsRoleQueuePaths.isUnderAgentsTree(it.relativePath) }
                excludeAgentsQueues ->
                    all.filter { !AgentsRoleQueuePaths.shouldSkipLocalPush(it.relativePath) }
                else -> all
            }
        }
        val changes = localClassifier.classify(snap, meta)
        var applied = 0
        val remoteByPath = remoteInMapping.associateBy { PathUtil.normalize(it.relativePath) }
        // Parent→children cache for match-before-create (push / walk miss).
        val siblingCache = mutableMapOf<String, List<RemoteEntry>>()

        // Win ApplyLocal Added: parents before children (shallowest-first) so resolveParentId
        // finds freshly upserted folder meta. Deepest-first was leftover from removed PruneCloudAbsent.
        val adds = changes.filterIsInstance<LocalChange.Added>()
            .sortedBy { it.entry.relativePath.count { c -> c == '/' } }
        val renames = changes.filterIsInstance<LocalChange.Renamed>()
        val updates = changes.filterIsInstance<LocalChange.Updated>()
        val removes = changes.filterIsInstance<LocalChange.Removed>()
            .sortedByDescending { it.meta.relativePath.count { c -> c == '/' } }

        for (r in renames) {
            coroutineContext.ensureActive()
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
            coroutineContext.ensureActive()
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
                    val bytes = try {
                        fs.readFile(mapping.localRootPath, a.entry.relativePath)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (SyncMissingLocalPath.isMissing(e)) {
                            SyncMissingLocalPath.log("ApplyLocalPhase.Added", path, path, e)
                            continue
                        }
                        throw e
                    }
                    val remoteSize = existingRemote.entry.sizeBytes
                    val localLooksStub =
                        bytes.isEmpty() || (bytes.size < 64 && remoteSize != null && remoteSize > bytes.size)
                    if (localLooksStub) {
                        val cloud = remote.downloadFile(existingRemote.entry.id)
                        if (cloud != null && cloud.isNotEmpty()) {
                            try {
                                fs.writeFile(mapping.localRootPath, a.entry.relativePath, cloud)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                if (SyncMissingLocalPath.isMissing(e)) {
                                    SyncMissingLocalPath.log("ApplyLocalPhase.Added", path, path, e)
                                    continue
                                }
                                throw e
                            }
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
                    // null = failed/not-found → soft-fail this item; isEmpty() is legitimate empty content.
                    val cloud = remote.downloadFile(existingRemote.entry.id)
                    if (cloud == null) {
                        SyncRemoteDownload.logFailed(
                            "ApplyLocalPhase.Added", path, existingRemote.entry.id
                        )
                        continue
                    }
                    val localHash = ContentHashUtil.sha256Hex(bytes)
                    val remoteHash = ContentHashUtil.sha256Hex(cloud)
                    try {
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
                            SyncDirectionDecide.Action.Read,
                            SyncDirectionDecide.Action.Skip -> {
                                // Read / Skip → adopt cloud when hashes differ (cloud-wins).
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
                                        remoteUpdateTime = existingRemote.entry.updateTime
                                            ?: Instant.now()
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (SyncMissingLocalPath.isMissing(e)) {
                            SyncMissingLocalPath.log("ApplyLocalPhase.Added", path, path, e)
                            continue
                        }
                        throw e
                    }
                }
                dirty.add(path)
                applied++
                continue
            }
            // Win reference (ApplyLocal Added): local-only path → create/upload (or soft-fail
            // sibling listing). Never recursive-delete solely because remote walk missed the path
            // (ADR sync-simple-win-reference). Force Download still uses pruneLocalAbsentFromRemote.
            val parentPath = PathUtil.parentOf(a.entry.relativePath)
            val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
            val name = PathUtil.nameOf(a.entry.relativePath)
            val (reuse, listingOk) = resolveExistingSibling(
                parentId, name, a.entry.isFolder, remoteInMapping, siblingCache
            )
            if (reuse == null && !listingOk) {
                android.util.Log.w(
                    "ProtoLinkSync",
                    "skip create $path: sibling listing failed under $parentId"
                )
                continue
            }
            val id: String
            try {
                if (reuse != null) {
                    id = reuse.id
                    if (!a.entry.isFolder) {
                        val bytes = fs.readFile(mapping.localRootPath, a.entry.relativePath)
                        if (bytes.isEmpty()) continue
                        remote.uploadFile(id, name, bytes, mimeOf(name))
                    }
                } else if (a.entry.isFolder) {
                    id = remote.addFolder(parentId, name)
                    rememberSibling(siblingCache, parentId, id, name, isFolder = true)
                } else {
                    val bytes = fs.readFile(mapping.localRootPath, a.entry.relativePath)
                    // Never push an empty / editor stub when we are about to adopt cloud content.
                    if (bytes.isEmpty()) continue
                    id = remote.addFile(parentId, name, bytes, mimeOf(name))
                    rememberSibling(siblingCache, parentId, id, name, isFolder = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (SyncMissingLocalPath.isMissing(e)) {
                    SyncMissingLocalPath.log("ApplyLocalPhase.Added", path, path, e)
                    continue
                }
                throw e
            }
            val addHash = if (a.entry.isFolder) {
                ""
            } else {
                a.entry.contentHash.ifEmpty {
                    ContentHashUtil.trySha256Hex {
                        fs.readFile(mapping.localRootPath, a.entry.relativePath)
                    } ?: run {
                        SyncMissingLocalPath.log("ApplyLocalPhase.Added", path, path)
                        ""
                    }
                }
            }
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = id,
                    parentRemoteId = parentId,
                    relativePath = PathUtil.normalize(a.entry.relativePath),
                    isFolder = a.entry.isFolder,
                    sizeBytes = a.entry.sizeBytes,
                    contentHash = addHash,
                    remoteUpdateTime = Instant.now()
                )
            )
            dirty.add(PathUtil.normalize(a.entry.relativePath))
            applied++
        }

        if (!skipSizeUpdates) {
            for (u in updates) {
                coroutineContext.ensureActive()
                val updatedPath = PathUtil.normalize(u.meta.relativePath)
                try {
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
                    dirty.add(updatedPath)
                    applied++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (SyncMissingLocalPath.isMissing(e)) {
                        SyncMissingLocalPath.log("ApplyLocalPhase.Updated", updatedPath, updatedPath, e)
                    } else {
                        throw e
                    }
                }
            }
        } else {
            // Folder size bookkeeping + same-size content uploads (hash-driven).
            for (u in updates.filter { it.meta.isFolder }) {
                coroutineContext.ensureActive()
                store.upsert(u.meta.copy(sizeBytes = u.newSize))
                applied++
            }
            for (u in updates.filter { !it.meta.isFolder }) {
                coroutineContext.ensureActive()
                // Content convergence is owned by applyComparePhase (always hash-verify).
                // Do not upload here — that overwrote unknown cloud content when download failed.
                store.upsert(u.meta.copy(sizeBytes = u.newSize))
            }
        }

        for (rm in removes) {
            coroutineContext.ensureActive()
            val removedPath = PathUtil.normalize(rm.meta.relativePath)
            // SAF peculiarity: ApplyRemote may have just materialized this path while File
            // snapshot still lags — do not DeleteEntity the same-pass download (Win CreateDirectory
            // is immediately visible so this guard is a no-op there).
            if (dirty.contains(removedPath)) continue
            remote.delete(rm.meta.remoteId)
            store.delete(mapping.id, rm.meta.relativePath)
            dirty.add(removedPath)
            applied++
        }

        return applied
    }

    private suspend fun applyRemotePhase(
        mapping: SyncMapping,
        mappingsById: Map<String, SyncMapping>,
        @Suppress("UNUSED_PARAMETER") remoteInMapping: List<RemoteChangeClassifier.RemoteLocated>,
        allRemote: List<RemoteChangeClassifier.RemoteLocated>,
        dirty: MutableSet<String>
    ): Int {
        val meta = store.getAll(mapping.id)
        val allIds = store.getAllRemoteIds()
        val cross = allRemote
        val changes = remoteClassifier.classify(meta, cross, allIds, dirty)

        var applied = 0
        for (c in changes) {
            coroutineContext.ensureActive()
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
                        val destPath = PathUtil.normalize(located.relativePath)
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
                    // Walk stops at Agents — skip materialize under whole Agents tree so
                    // Api-only fixtures at map root reach LastSync (1.0.115 DOWNLOAD residual).
                    if (AgentsRoleQueuePaths.isUnderAgentsTree(c.relativePath)) {
                        SyncRemoteDownload.logMaterializeFailed(
                            "ApplyRemotePhase.AgentsTreeSkip",
                            c.relativePath,
                            c.remote.id,
                            "skip Sync materialize under Agents tree"
                        )
                        continue
                    }
                    try {
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
                            // Win DownloadBytesOrNullAsync ?? Empty + WriteAllBytes (incl. empty).
                            val bytes = remote.downloadFile(c.remote.id)
                            if (bytes == null) {
                                SyncRemoteDownload.logFailed(
                                    "ApplyRemotePhase.Added", c.relativePath, c.remote.id
                                )
                                continue
                            }
                            fs.writeFile(mapping.localRootPath, c.relativePath, bytes)
                            store.upsert(
                                SyncItemMeta(
                                    mappingId = mapping.id,
                                    remoteId = c.remote.id,
                                    parentRemoteId = c.remote.parentId,
                                    relativePath = c.relativePath,
                                    isFolder = false,
                                    sizeBytes = bytes.size.toLong(),
                                    contentHash = if (bytes.isNotEmpty()) {
                                        ContentHashUtil.sha256Hex(bytes)
                                    } else {
                                        ""
                                    },
                                    remoteUpdateTime = c.remote.updateTime
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        SyncRemoteDownload.logMaterializeFailed(
                            "ApplyRemotePhase.Added",
                            c.relativePath,
                            c.remote.id,
                            e.message ?: e.javaClass.simpleName
                        )
                        continue
                    }
                    // Mark materialized so same-pass ApplyLocal snapshot lag cannot Removed→DeleteEntity.
                    dirty.add(PathUtil.normalize(c.relativePath))
                    applied++
                }
                is RemoteChange.Removed -> {
                    if (c.meta.mappingId != mapping.id) continue
                    if (cross.any { it.entry.id == c.meta.remoteId }) continue
                    // Walk stops BFS at Agents folder — queue protect alone left Agents/docs/**
                    // warm meta as false Removed → SAF delete storm before Added (1.0.115 FAIL).
                    if (AgentsRoleQueuePaths.isUnderAgentsTree(c.meta.relativePath) ||
                        AgentsRoleQueuePaths.isForcePruneProtected(c.meta.relativePath, null)
                    ) {
                        continue
                    }
                    try {
                        if (fs.exists(mapping.localRootPath, c.meta.relativePath)) {
                            fs.delete(mapping.localRootPath, c.meta.relativePath, c.meta.isFolder)
                        }
                        store.delete(mapping.id, c.meta.relativePath)
                        applied++
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // NFR-SYNC-1: one gone/locked path must not abort cloud-delete converge.
                        SyncRemoteDownload.logMaterializeFailed(
                            "ApplyRemotePhase.Removed",
                            c.meta.relativePath,
                            c.meta.remoteId,
                            e.message ?: e.javaClass.simpleName
                        )
                    }
                }
                is RemoteChange.Updated -> {
                    // Size/time content sync already handled in applyComparePhase.
                    // Keep as safety net only when local still matches meta (unchanged locally).
                    if (c.meta.mappingId != mapping.id) continue
                    if (c.meta.isFolder) continue
                    if (dirty.contains(PathUtil.normalize(c.meta.relativePath))) continue
                    // Win: FileInfo.Length one path — not full-tree snapshot per Updated item.
                    val localLen = fs.fileLengthBytes(mapping.localRootPath, c.meta.relativePath)
                    if (localLen != null && localLen != c.meta.sizeBytes) continue
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

    /**
     * Depth-first remote inventory with parallel sibling folder listings (Win WalkRemoteParallel parity).
     * When meta is non-empty: structure-only GetEntities when every child id is already known;
     * otherwise one IncludeValues re-list (Win values-light). Cold bootstrap (empty meta) still
     * IncludeValues-lists. Force uses structure-first + Ids name waves (progressive materialize).
     *
     * Warm Sync ([progressiveMaterialize]=true): materialize Api-only kids as soon as listed,
     * before sibling/deep BFS awaits — phone Walk latency otherwise leaves ApplyRemote after
     * the Sync chrome wait (1.0.118 DOWNLOAD late SAF / sticky Syncing).
     * [visitedIds]/[seenPaths] stop cycles.
     */
    private suspend fun walkRemote(
        mapping: SyncMapping,
        relativeParent: String,
        out: MutableList<RemoteChangeClassifier.RemoteLocated>,
        progressiveMaterialize: Boolean = true,
        progressiveDirty: MutableSet<String>? = null
    ) {
        val visitedIds = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        val gate = Mutex()
        val throttle = Semaphore(WALK_REMOTE_CONCURRENCY)
        val metaByRemoteId = store.getAll(mapping.id)
            .groupBy { it.remoteId }
            .mapValues { (_, items) -> items.first() }
            .toMutableMap()
        walkRemoteParallel(
            mapping,
            mapping.cloudFolderId,
            relativeParent,
            out,
            visitedIds,
            seenPaths,
            gate,
            throttle,
            metaByRemoteId,
            progressiveMaterialize,
            progressiveDirty
        )
    }

    /**
     * ApplyRemote Added for one Walk discovery (folder mkdir or file getFile). Soft-fails per
     * item (NFR-SYNC-1). Updates [metaByRemoteId] so later Walk folders see the id as known.
     */
    private suspend fun materializeWalkDiscoveredAdded(
        mapping: SyncMapping,
        located: RemoteChangeClassifier.RemoteLocated,
        metaByRemoteId: MutableMap<String, SyncItemMeta>,
        gate: Mutex,
        progressiveDirty: MutableSet<String>?
    ) {
        val path = PathUtil.normalize(located.relativePath)
        if (AgentsRoleQueuePaths.isUnderAgentsTree(path)) return
        if (metaByRemoteId.containsKey(located.entry.id)) return
        try {
            val upserted = if (located.entry.isFolder) {
                fs.createDirectory(mapping.localRootPath, path)
                // Do not upsert meta on a phantom mkdir — ApplyRemote Added must retry
                // (1.0.119 DOWNLOAD lateSAF=NO when meta claimed land without SAF/File).
                if (!fs.exists(mapping.localRootPath, path)) {
                    SyncRemoteDownload.logMaterializeFailed(
                        "WalkRemote.ProgressiveAdded",
                        path,
                        located.entry.id,
                        "createDirectory did not materialize path"
                    )
                    return
                }
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = located.entry.id,
                    parentRemoteId = located.entry.parentId,
                    relativePath = path,
                    isFolder = true,
                    sizeBytes = 0,
                    remoteUpdateTime = located.entry.updateTime
                )
            } else {
                val bytes = remote.downloadFile(located.entry.id)
                if (bytes == null) {
                    SyncRemoteDownload.logFailed(
                        "WalkRemote.ProgressiveAdded", path, located.entry.id
                    )
                    return
                }
                fs.writeFile(mapping.localRootPath, path, bytes)
                if (!fs.exists(mapping.localRootPath, path)) {
                    SyncRemoteDownload.logMaterializeFailed(
                        "WalkRemote.ProgressiveAdded",
                        path,
                        located.entry.id,
                        "writeFile did not materialize path"
                    )
                    return
                }
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = located.entry.id,
                    parentRemoteId = located.entry.parentId,
                    relativePath = path,
                    isFolder = false,
                    sizeBytes = bytes.size.toLong(),
                    contentHash = if (bytes.isNotEmpty()) {
                        ContentHashUtil.sha256Hex(bytes)
                    } else {
                        ""
                    },
                    remoteUpdateTime = located.entry.updateTime
                )
            }
            store.upsert(upserted)
            gate.withLock {
                metaByRemoteId[located.entry.id] = upserted
                progressiveDirty?.add(path)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncRemoteDownload.logMaterializeFailed(
                "WalkRemote.ProgressiveAdded",
                path,
                located.entry.id,
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private suspend fun walkRemoteParallel(
        mapping: SyncMapping,
        folderId: String,
        relativeParent: String,
        out: MutableList<RemoteChangeClassifier.RemoteLocated>,
        visitedIds: MutableSet<String>,
        seenPaths: MutableSet<String>,
        gate: Mutex,
        throttle: Semaphore,
        metaByRemoteId: MutableMap<String, SyncItemMeta>,
        progressiveMaterialize: Boolean,
        progressiveDirty: MutableSet<String>?
    ) {
        coroutineContext.ensureActive()
        // Cold (empty meta): IncludeValues listing per folder.
        // Warm reconcile: structure-only first; if every child is already in meta, reuse
        // RelativePath names. Any unknown child → one IncludeValues re-list for that folder.
        val warm = metaByRemoteId.isNotEmpty()
        // Agents queue parents: never IncludeValues re-list (AgentTeam churn → unknown kids
        // forever). Same exclude class as AutoPush/Force; live 1.0.114 sticky Syncing.
        val agentsParent = relativeParent.isNotEmpty() &&
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(relativeParent)
        var children = throttle.withPermit {
            remote.listChildrenPaged(folderId, includeValues = !warm)
        }
        var useMetaNames = false
        if (warm) {
            val allKnown = children.isEmpty() || children.all { metaByRemoteId.containsKey(it.id) }
            if (allKnown || agentsParent) {
                useMetaNames = true
                if (agentsParent && !allKnown) {
                    children = children.filter { metaByRemoteId.containsKey(it.id) }
                }
            } else {
                children = throttle.withPermit {
                    remote.listChildrenPaged(folderId, includeValues = true)
                }
            }
        }
        val subfolders = mutableListOf<Pair<String, String>>()
        val progressive = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        gate.withLock {
            for (child in children) {
                if (!visitedIds.add(child.id)) continue
                val name = if (useMetaNames) {
                    val known = metaByRemoteId[child.id]
                    if (known != null) PathUtil.nameOf(known.relativePath) else child.name
                } else {
                    // IncludeValues listing: skip synthetic file-{id} (Win IsSyntheticCloudFileDisplayName).
                    if (!child.isFolder && isSyntheticCloudFileDisplayName(child.id, child.name)) continue
                    child.name
                }
                if (name.isEmpty()) continue
                val rel = PathUtil.join(relativeParent, name)
                if (!seenPaths.add(PathUtil.normalize(rel))) continue
                // Warm values-light (useMetaNames): structure-only UpdateTime must not drive
                // ApplyCompare getFile. Trust meta baseline; IncludeValues re-list keeps time.
                val listingUpdate = if (useMetaNames) null else child.updateTime
                val located = RemoteChangeClassifier.RemoteLocated(
                    entry = child.copy(
                        parentId = folderId,
                        name = name,
                        updateTime = listingUpdate
                    ),
                    mappingId = mapping.id,
                    relativePath = rel
                )
                out.add(located)
                if (progressiveMaterialize &&
                    !metaByRemoteId.containsKey(child.id) &&
                    !AgentsRoleQueuePaths.isUnderAgentsTree(rel)
                ) {
                    progressive.add(located)
                }
                if (child.isFolder &&
                    !AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(rel)
                ) {
                    // Do not BFS under Agents role/mailbox queues (Force open-queue stop class).
                    subfolders.add(child.id to rel)
                }
            }
        }
        // Materialize unknowns before deep/sibling BFS so map-root Api plants land under SAF
        // while Walk continues (1.0.118 sticky Syncing / late SAF residual).
        for (loc in progressive) {
            coroutineContext.ensureActive()
            materializeWalkDiscoveredAdded(
                mapping, loc, metaByRemoteId, gate, progressiveDirty
            )
        }
        if (subfolders.isEmpty()) return
        coroutineScope {
            subfolders.map { (id, rel) ->
                async {
                    walkRemoteParallel(
                        mapping,
                        id,
                        rel,
                        out,
                        visitedIds,
                        seenPaths,
                        gate,
                        throttle,
                        metaByRemoteId,
                        progressiveMaterialize,
                        progressiveDirty
                    )
                }
            }.awaitAll()
        }
    }

    private data class StructureEdge(
        val id: String,
        val parentId: String,
        val isFolder: Boolean
    )

    /**
     * Force inventory: structure-only BFS with batched ParentIdsMatchAny listings (heal-safe),
     * then Ids IncludeValues name waves ([FORCE_NAME_BATCH] × [FORCE_NAME_CONCURRENCY]) with
     * meta name-cache. Prefer [forcePullMapping] progressive path (name wave → materialize)
     * so downloadFile starts before the full name pass finishes.
     */
    private suspend fun walkRemoteForceInventory(
        rootFolderId: String,
        mappingId: String,
        out: MutableList<RemoteChangeClassifier.RemoteLocated>
    ) {
        val edges = collectForceStructureEdges(rootFolderId)
        val metaNames = forceMetaNameSeed(mappingId)
        val byParent = edges.groupBy { it.parentId }
        val names = mutableMapOf<String, String>()
        names.putAll(metaNames)
        val seenPaths = mutableSetOf<String>()
        var frontier = listOf(rootFolderId to "")
        while (frontier.isNotEmpty()) {
            val waveKids = frontier.flatMap { (folderId, relativeParent) ->
                byParent[folderId].orEmpty().map { kid -> Triple(kid, folderId, relativeParent) }
            }
            val missing = waveKids.map { it.first.id }.filter { id ->
                names[id].isNullOrEmpty()
            }.distinct()
            if (missing.isNotEmpty()) {
                names.putAll(resolveDisplayNamesByIds(missing, seed = names))
            }
            val next = mutableListOf<Pair<String, String>>()
            for ((kid, folderId, relativeParent) in waveKids) {
                val name = forceResolveChildName(kid.id, kid.isFolder, names) ?: continue
                if (name.isEmpty()) continue
                val rel = PathUtil.join(relativeParent, name)
                if (!seenPaths.add(PathUtil.normalize(rel))) continue
                out.add(
                    RemoteChangeClassifier.RemoteLocated(
                        entry = ru.protolink.communicator.sync.model.RemoteEntry(
                            id = kid.id,
                            parentId = folderId,
                            name = name,
                            isFolder = kid.isFolder
                        ),
                        mappingId = mappingId,
                        relativePath = rel
                    )
                )
                if (kid.isFolder) next.add(kid.id to rel)
            }
            frontier = next
        }
    }

    private suspend fun collectForceStructureEdges(rootFolderId: String): List<StructureEdge> {
        val edges = mutableListOf<StructureEdge>()
        val visitedIds = mutableSetOf<String>()
        val throttle = Semaphore(WALK_REMOTE_CONCURRENCY)
        walkRemoteStructureBatched(
            rootFolderId = rootFolderId,
            out = edges,
            visitedIds = visitedIds,
            throttle = throttle
        )
        return edges
    }

    /** RelativePath leaf names keyed by remoteId — skip IncludeValues when meta already knows. */
    private fun forceMetaNameSeed(mappingId: String): Map<String, String> =
        store.getAll(mappingId).mapNotNull { meta ->
            val name = PathUtil.nameOf(meta.relativePath).trim()
            if (name.isEmpty()) null else meta.remoteId to name
        }.toMap()

    /** Win GetDisplayName / Id:N — GUID without dashes. */
    private fun cloudIdCompact(id: String): String = id.replace("-", "")

    /** Win IsSyntheticCloudFileDisplayName — file-{id:N} is not a real on-disk name. */
    private fun isSyntheticCloudFileDisplayName(id: String, displayName: String): Boolean =
        displayName.equals("file-${cloudIdCompact(id)}", ignoreCase = true)

    /**
     * Force inventory name after Ids IncludeValues (and soft-fail). Win IncludeValues policy:
     * folders keep [folder-{id}]; synthetic files only when IncludeValues returned a real Values
     * name — never invent file-{id} after Ids soft-fail (that silently dropped Force children).
     */
    private fun forceResolveChildName(
        id: String,
        isFolder: Boolean,
        names: Map<String, String>
    ): String? {
        val known = names[id]?.trim().orEmpty()
        if (known.isNotEmpty()) {
            if (!isFolder && isSyntheticCloudFileDisplayName(id, known)) {
                SyncInventoryFetch.logSoftFail(
                    "ForceIdsNames",
                    id,
                    "synthetic file name skipped"
                )
                return null
            }
            return known
        }
        if (isFolder) {
            SyncInventoryFetch.logSoftFail(
                "ForceIdsNames",
                id,
                "missing name — using folder-${cloudIdCompact(id)}"
            )
            return "folder-${cloudIdCompact(id)}"
        }
        SyncInventoryFetch.logSoftFail("ForceIdsNames", id, "missing name — skip file")
        return null
    }

    /**
     * Root listed alone (hard). Nested parents BFS-batched via [listChildrenOfParentsForceInventory]
     * with MatchAny probe — falls back to 1/parent if deployed Api lacks ParentIdsMatchAny.
     */
    private suspend fun walkRemoteStructureBatched(
        rootFolderId: String,
        out: MutableList<StructureEdge>,
        visitedIds: MutableSet<String>,
        throttle: Semaphore
    ) {
        val rootChildren = throttle.withPermit {
            listChildrenForceInventory(rootFolderId, softFailOnExhausted = false)
        }
        val pending = ArrayDeque<String>()
        for (child in rootChildren) {
            if (!visitedIds.add(child.id)) continue
            out.add(StructureEdge(child.id, rootFolderId, child.isFolder))
            if (child.isFolder) pending.add(child.id)
        }

        // null = not probed yet; false = old Api (AND) — stay 1/parent for this Force pass.
        var matchAnyEnabled: Boolean? = null
        while (pending.isNotEmpty()) {
            val batch = ArrayList<String>(FORCE_PARENT_BATCH)
            while (pending.isNotEmpty() && batch.size < FORCE_PARENT_BATCH) {
                batch.add(pending.removeFirst())
            }
            val children = when {
                matchAnyEnabled == false || batch.size == 1 ->
                    listChildrenForceInventoryParallel(batch, throttle)
                else -> {
                    var batched = try {
                        throttle.withPermit {
                            // One 5xx → throw → per-parent soft-fail (BATCH_MAX_ATTEMPTS).
                            // Do not burn 4×gateway timeouts on a fat MatchAny page.
                            listChildrenOfParentsForceInventory(
                                batch,
                                softFailOnExhausted = false,
                                maxAttempts = SyncInventoryFetch.BATCH_MAX_ATTEMPTS
                            )
                        }
                    } catch (t: Throwable) {
                        if (SyncInventoryFetch.isHardClientError(t)) throw t
                        if (!SyncInventoryFetch.isTransientServerError(t) &&
                            t !is SyncException
                        ) {
                            throw t
                        }
                        SyncInventoryFetch.logSoftFail(
                            "ForceStructureBatch",
                            batch.first() + "+${batch.size - 1}",
                            t.message ?: t.javaClass.simpleName
                        )
                        listChildrenForceInventoryParallel(batch, throttle)
                    }
                    if (matchAnyEnabled == null && batched.isEmpty() && batch.size > 1) {
                        val single = throttle.withPermit {
                            listChildrenForceInventory(batch[0], softFailOnExhausted = true)
                        }
                        if (single.isNotEmpty()) {
                            System.err.println(
                                "[SyncEngine] ParentIdsMatchAny unsupported (empty multi-parent " +
                                    "batch, non-empty single) — Force falls back to 1 GE/parent"
                            )
                            matchAnyEnabled = false
                            batched = listChildrenForceInventoryParallel(batch, throttle)
                        } else {
                            matchAnyEnabled = true
                        }
                    } else if (matchAnyEnabled == null) {
                        matchAnyEnabled = true
                    }
                    batched
                }
            }
            for (child in children) {
                if (!visitedIds.add(child.id)) continue
                out.add(StructureEdge(child.id, child.parentId, child.isFolder))
                if (child.isFolder) pending.add(child.id)
            }
        }
    }

    private suspend fun listChildrenForceInventoryParallel(
        folderIds: List<String>,
        throttle: Semaphore,
        softFailedOut: MutableSet<String>? = null
    ): List<RemoteEntry> = coroutineScope {
        folderIds.map { id ->
            async {
                throttle.withPermit {
                    listChildrenForceInventory(id, softFailOnExhausted = true, softFailedOut)
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Structure-only GetEntities with backoff/retry on transient HTTP 5xx.
     * Soft-fail returns empty children (skip subtree); hard client errors and non-5xx rethrow.
     * Soft-fail empty ≠ proven-empty — [softFailedOut] records unknown parents for Force prune skip.
     */
    private suspend fun listChildrenForceInventory(
        folderId: String,
        softFailOnExhausted: Boolean,
        softFailedOut: MutableSet<String>? = null
    ): List<RemoteEntry> {
        var last: Throwable? = null
        for (attempt in 0 until SyncInventoryFetch.MAX_ATTEMPTS) {
            val wait = SyncInventoryFetch.backoffMs(attempt)
            if (wait > 0L) delay(wait)
            try {
                return remote.listChildrenPaged(folderId, includeValues = false)
            } catch (t: Throwable) {
                if (SyncInventoryFetch.isHardClientError(t)) throw t
                if (!SyncInventoryFetch.isTransientServerError(t)) throw t
                last = t
                SyncInventoryFetch.logRetry(
                    "ForceStructure",
                    folderId,
                    attempt + 1,
                    t.message ?: t.javaClass.simpleName
                )
            }
        }
        val detail = last?.message ?: last?.javaClass?.simpleName ?: "exhausted"
        if (softFailOnExhausted) {
            SyncInventoryFetch.logSoftFail("ForceStructure", folderId, detail)
            softFailedOut?.add(folderId)
            return emptyList()
        }
        throw last ?: SyncException("Force inventory GetEntities failed for root folder.")
    }

    /** Batched ParentIdsMatchAny structure list with same 5xx retry / soft-fail. */
    private suspend fun listChildrenOfParentsForceInventory(
        folderIds: List<String>,
        softFailOnExhausted: Boolean,
        maxAttempts: Int = SyncInventoryFetch.MAX_ATTEMPTS
    ): List<RemoteEntry> {
        if (folderIds.isEmpty()) return emptyList()
        if (folderIds.size == 1) {
            return listChildrenForceInventory(folderIds[0], softFailOnExhausted)
        }
        val attempts = maxAttempts.coerceAtLeast(1)
        val key = folderIds.first() + "+${folderIds.size - 1}"
        var last: Throwable? = null
        for (attempt in 0 until attempts) {
            val wait = SyncInventoryFetch.backoffMs(attempt)
            if (wait > 0L) delay(wait)
            try {
                return remote.listChildrenOfParents(folderIds, includeValues = false)
            } catch (t: Throwable) {
                if (SyncInventoryFetch.isHardClientError(t)) throw t
                if (!SyncInventoryFetch.isTransientServerError(t)) throw t
                last = t
                SyncInventoryFetch.logRetry(
                    "ForceStructureBatch",
                    key,
                    attempt + 1,
                    t.message ?: t.javaClass.simpleName
                )
            }
        }
        val detail = last?.message ?: last?.javaClass?.simpleName ?: "exhausted"
        if (softFailOnExhausted) {
            SyncInventoryFetch.logSoftFail("ForceStructureBatch", key, detail)
            return emptyList()
        }
        throw last ?: SyncException("Force inventory batched GetEntities failed.")
    }

    /**
     * Structure-list one Force BFS wave of parents (MatchAny batches + 5xx → per-parent soft-fail).
     * Returns children keyed by parent id. [matchAnyEnabled] null = probe, false = 1 GE/parent.
     * [softFailedOut] receives parent ids whose inventory soft-failed to empty (unknown, not proven empty).
     */
    private suspend fun listChildrenForceWaveByParent(
        parentIds: List<String>,
        throttle: Semaphore,
        matchAnyEnabled: Boolean?,
        softFailedOut: MutableSet<String>
    ): Pair<Map<String, List<RemoteEntry>>, Boolean?> {
        if (parentIds.isEmpty()) return emptyMap<String, List<RemoteEntry>>() to matchAnyEnabled
        val distinct = parentIds.distinct()
        val byParent = linkedMapOf<String, MutableList<RemoteEntry>>()
        fun remember(entries: List<RemoteEntry>, fallbackParent: String?) {
            for (e in entries) {
                val pid = e.parentId.takeIf { it.isNotBlank() }
                    ?: fallbackParent
                    ?: continue
                byParent.getOrPut(pid) { mutableListOf() }.add(e)
            }
        }
        var matchAny = matchAnyEnabled
        for (batch in distinct.chunked(FORCE_PARENT_BATCH)) {
            val children = when {
                matchAny == false || batch.size == 1 ->
                    listChildrenForceInventoryParallel(batch, throttle, softFailedOut)
                else -> {
                    var batched = try {
                        throttle.withPermit {
                            listChildrenOfParentsForceInventory(
                                batch,
                                softFailOnExhausted = false,
                                maxAttempts = SyncInventoryFetch.BATCH_MAX_ATTEMPTS
                            )
                        }
                    } catch (t: Throwable) {
                        if (SyncInventoryFetch.isHardClientError(t)) throw t
                        if (!SyncInventoryFetch.isTransientServerError(t) &&
                            t !is SyncException
                        ) {
                            throw t
                        }
                        SyncInventoryFetch.logSoftFail(
                            "ForceStructureWave",
                            batch.first() + "+${batch.size - 1}",
                            t.message ?: t.javaClass.simpleName
                        )
                        listChildrenForceInventoryParallel(batch, throttle, softFailedOut)
                    }
                    if (matchAny == null && batched.isEmpty() && batch.size > 1) {
                        val single = throttle.withPermit {
                            listChildrenForceInventory(
                                batch[0],
                                softFailOnExhausted = true,
                                softFailedOut
                            )
                        }
                        if (single.isNotEmpty()) {
                            System.err.println(
                                "[SyncEngine] ParentIdsMatchAny unsupported (empty multi-parent " +
                                    "batch, non-empty single) — Force wave falls back to 1 GE/parent"
                            )
                            matchAny = false
                            batched = listChildrenForceInventoryParallel(batch, throttle, softFailedOut)
                        } else {
                            matchAny = true
                        }
                    } else if (matchAny == null) {
                        matchAny = true
                    }
                    batched
                }
            }
            // Parallel path returns entries with correct parentId; MatchAny too.
            // If parentId blank (Ids-only), attribute to sole batch parent.
            val sole = batch.singleOrNull()
            remember(children, sole)
        }
        return byParent.mapValues { it.value.toList() } to matchAny
    }

    private suspend fun resolveDisplayNamesByIds(
        ids: List<String>,
        seed: Map<String, String> = emptyMap()
    ): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val names = mutableMapOf<String, String>()
        for (id in ids) {
            val seeded = seed[id]?.trim().orEmpty()
            if (seeded.isNotEmpty()) names[id] = seeded
        }
        val missing = ids.filter { names[it].isNullOrEmpty() }.distinct()
        if (missing.isEmpty()) return names
        val gate = Mutex()
        val throttle = Semaphore(FORCE_NAME_CONCURRENCY)
        coroutineScope {
            missing.chunked(FORCE_NAME_BATCH).map { chunk ->
                async {
                    val rows = throttle.withPermit {
                        getEntitiesByIdsForceInventory(chunk)
                    }
                    gate.withLock {
                        for (row in rows) {
                            if (row.name.isNotEmpty()) names[row.id] = row.name
                        }
                    }
                }
            }.awaitAll()
        }
        return names
    }

    /**
     * Ids IncludeValues with 5xx retry; on exhaust, half-split once the chunk is >1 so a
     * gateway 504 on a fat page does not soft-lose the whole name set (NFR-SYNC-1).
     * Single-id exhaust → soft-fail empty (edge skipped).
     */
    private suspend fun getEntitiesByIdsForceInventory(ids: List<String>): List<RemoteEntry> {
        if (ids.isEmpty()) return emptyList()
        val key = ids.firstOrNull().orEmpty() + if (ids.size > 1) "+${ids.size - 1}" else ""
        var last: Throwable? = null
        for (attempt in 0 until SyncInventoryFetch.MAX_ATTEMPTS) {
            val wait = SyncInventoryFetch.backoffMs(attempt)
            if (wait > 0L) delay(wait)
            try {
                return remote.getEntitiesByIds(ids, includeValues = true)
            } catch (t: Throwable) {
                if (SyncInventoryFetch.isHardClientError(t)) throw t
                if (!SyncInventoryFetch.isTransientServerError(t)) throw t
                last = t
                SyncInventoryFetch.logRetry(
                    "ForceIdsNames",
                    key,
                    attempt + 1,
                    t.message ?: t.javaClass.simpleName
                )
            }
        }
        val detail = last?.message ?: last?.javaClass?.simpleName ?: "exhausted"
        if (ids.size > 1) {
            val mid = ids.size / 2
            SyncInventoryFetch.logRetry(
                "ForceIdsNamesSplit",
                key,
                1,
                "half-split after $detail"
            )
            return getEntitiesByIdsForceInventory(ids.subList(0, mid)) +
                getEntitiesByIdsForceInventory(ids.subList(mid, ids.size))
        }
        SyncInventoryFetch.logSoftFail("ForceIdsNames", key, detail)
        return emptyList()
    }

    private fun resolveParentId(mapping: SyncMapping, parentPath: String): String? {
        if (parentPath.isEmpty()) return mapping.cloudFolderId
        val existing = store.getAll(mapping.id)
            .firstOrNull { PathUtil.normalize(it.relativePath) == PathUtil.normalize(parentPath) }
        return existing?.remoteId
    }

    /**
     * Match-before-create: walk list first, then live parent listChildren (cached).
     * Match null + listingOk false → soft-fail (do not create a possible duplicate).
     */
    private suspend fun resolveExistingSibling(
        parentId: String,
        displayName: String,
        isFolder: Boolean,
        remoteInMapping: List<RemoteChangeClassifier.RemoteLocated>,
        siblingCache: MutableMap<String, List<RemoteEntry>>
    ): Pair<RemoteEntry?, Boolean> {
        val fromWalk = SameNamedCloudSibling.preferExisting(
            parentId,
            displayName,
            isFolder,
            remoteInMapping.map { it.entry }
        )
        if (fromWalk != null) return fromWalk to true

        val cachedKey = siblingCache.keys.firstOrNull { it.equals(parentId, ignoreCase = true) }
        if (cachedKey != null) {
            val kids = siblingCache.getValue(cachedKey)
            return SameNamedCloudSibling.preferExisting(parentId, displayName, isFolder, kids) to true
        }

        return try {
            val kids = remote.listChildrenPaged(parentId, includeValues = true)
                .filter { it.name.isNotBlank() }
            siblingCache[parentId] = kids
            SameNamedCloudSibling.preferExisting(parentId, displayName, isFolder, kids) to true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w(
                "ProtoLinkSync",
                "sibling list failed parent=$parentId: ${e.message ?: e.javaClass.simpleName}"
            )
            null to false
        }
    }

    private fun rememberSibling(
        siblingCache: MutableMap<String, List<RemoteEntry>>,
        parentId: String,
        id: String,
        name: String,
        isFolder: Boolean
    ) {
        val key = siblingCache.keys.firstOrNull { it.equals(parentId, ignoreCase = true) } ?: parentId
        val prev = siblingCache[key].orEmpty()
        siblingCache[key] = prev + RemoteEntry(
            id = id,
            parentId = parentId,
            name = name,
            isFolder = isFolder,
            updateTime = Instant.now()
        )
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html", true) -> "text/html"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }

    /** Overwrite server with one local file; meta follows local. */
    suspend fun forcePushPath(mapping: SyncMapping, relativePath: String) {
        val path = PathUtil.normalize(relativePath)
        if (!fs.exists(mapping.localRootPath, path)) {
            throw SyncException("Local file missing for conflict resolve.", relativePath = path)
        }
        val bytes = fs.readFile(mapping.localRootPath, path)
        val hash = ContentHashUtil.sha256Hex(bytes)
        val parentPath = PathUtil.parentOf(path)
        val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
        val name = PathUtil.nameOf(path)
        val existing = store.getAll(mapping.id)
            .firstOrNull { PathUtil.normalize(it.relativePath) == path && !it.isFolder }
        val siblingCache = mutableMapOf<String, List<RemoteEntry>>()
        val remoteId = if (existing != null) {
            remote.uploadFile(existing.remoteId, name, bytes, mimeOf(name))
            existing.remoteId
        } else {
            val (reuse, listingOk) = resolveExistingSibling(
                parentId, name, isFolder = false, emptyList(), siblingCache
            )
            if (reuse == null && !listingOk) {
                throw SyncException("Sibling listing failed; refused duplicate create.", relativePath = path)
            }
            if (reuse != null) {
                remote.uploadFile(reuse.id, name, bytes, mimeOf(name))
                reuse.id
            } else {
                remote.addFile(parentId, name, bytes, mimeOf(name))
            }
        }
        store.upsert(
            SyncItemMeta(
                mappingId = mapping.id,
                remoteId = remoteId,
                parentRemoteId = existing?.parentRemoteId ?: parentId,
                relativePath = path,
                isFolder = false,
                sizeBytes = bytes.size.toLong(),
                contentHash = hash,
                remoteUpdateTime = Instant.now()
            )
        )
    }

    /** Overwrite local file from server; meta follows remote. */
    suspend fun forcePullPath(mapping: SyncMapping, relativePath: String) {
        val path = PathUtil.normalize(relativePath)
        val located = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
        // Structure-first + Ids names — heal-safe (no stale-meta warm trust).
        walkRemoteForceInventory(mapping.cloudFolderId, mapping.id, located)
        val loc = located.firstOrNull {
            PathUtil.normalize(it.relativePath) == path && !it.entry.isFolder
        } ?: throw SyncException("Remote file missing for conflict resolve.", relativePath = path)
        val bytes = remote.downloadFile(loc.entry.id)
            ?: throw SyncException("Force download failed: remote file missing.", relativePath = path)
        if (bytes.isEmpty()) {
            throw SyncException("Force download returned empty file.", relativePath = path)
        }
        val parent = PathUtil.parentOf(path)
        if (parent.isNotEmpty() && !fs.exists(mapping.localRootPath, parent)) {
            fs.createDirectory(mapping.localRootPath, parent)
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
    }

    /** Overwrite server with all local files; meta follows local. Returns files uploaded. */
    suspend fun forcePushMapping(mapping: SyncMapping): Int {
        // Size/structure first; hash each file at upload (readFile). Win SnapshotFs hashes via
        // fast File IO — SAF openInputStream×whole MyNotesFolder before first Api was the
        // FORCE_UPLOAD_STUCK_NO_PROGRESS residual (same decision table, SAF cost model only).
        val snap = fs.snapshot(mapping.localRootPath, computeContentHashes = false)
            .sortedWith(compareBy({ it.relativePath.count { c -> c == '/' } }, { if (it.isFolder) 0 else 1 }))
        if (snap.isEmpty()) {
            throw SyncException(
                "Force upload found no local files. Check the mapped folder SAF permission."
            )
        }
        var files = 0
        val siblingCache = mutableMapOf<String, List<RemoteEntry>>()
        for (entry in snap) {
            coroutineContext.ensureActive()
            val path = PathUtil.normalize(entry.relativePath)
            val parentPath = PathUtil.parentOf(path)
            val parentId = resolveParentId(mapping, parentPath) ?: mapping.cloudFolderId
            val name = PathUtil.nameOf(path)
            val existing = store.getAll(mapping.id)
                .firstOrNull { PathUtil.normalize(it.relativePath) == path }
            if (entry.isFolder) {
                if (existing != null) continue
                val (reuseFolder, folderListingOk) = resolveExistingSibling(
                    parentId, name, isFolder = true, emptyList(), siblingCache
                )
                if (reuseFolder == null && !folderListingOk) continue
                val id = if (reuseFolder != null) {
                    reuseFolder.id
                } else {
                    remote.addFolder(parentId, name).also {
                        rememberSibling(siblingCache, parentId, it, name, isFolder = true)
                    }
                }
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
            val bytes = try {
                fs.readFile(mapping.localRootPath, path)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (SyncMissingLocalPath.isMissing(e)) {
                    SyncMissingLocalPath.log("ForcePush", path, path, e)
                    continue
                }
                throw e
            }
            val hash = ContentHashUtil.sha256Hex(bytes)
            val remoteId = try {
                if (existing != null) {
                    remote.uploadFile(existing.remoteId, name, bytes, mimeOf(name))
                    existing.remoteId
                } else {
                    val (reuseFile, fileListingOk) = resolveExistingSibling(
                        parentId, name, isFolder = false, emptyList(), siblingCache
                    )
                    if (reuseFile == null && !fileListingOk) continue
                    if (reuseFile != null) {
                        remote.uploadFile(reuseFile.id, name, bytes, mimeOf(name))
                        reuseFile.id
                    } else {
                        remote.addFile(parentId, name, bytes, mimeOf(name)).also {
                            rememberSibling(siblingCache, parentId, it, name, isFolder = false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (SyncMissingLocalPath.isMissing(e)) {
                    SyncMissingLocalPath.log("ForcePush", path, path, e)
                    continue
                }
                throw e
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
        // Win ForcePushMappingCoreAsync does not throw when the tree is folders-only — it just
        // finishes. Android-only "0 files" abort hid Sync delete residuals behind Force chrome
        // (tester 1.0.111: "Force upload processed 0 files (folders only or empty tree).").
        store.setLastSyncUtc(mapping.id, Instant.now())
        return files
    }

    /**
     * Overwrite local FS with all remote files; meta follows remote. Returns files downloaded.
     * Null download soft-fails per item and continues (NFR-SYNC-1) — empty bytes are legitimate.
     * After pull, local paths absent from remote are pruned (Force download = mirror).
     */
    suspend fun forcePullMapping(mapping: SyncMapping): Int {
        // Progressive BFS: list → name → mkdir → early prune → deepen → selective getFile.
        // Live 1.0.62: full-tree getFile never Force-complete on 898c1453 (~24m, agents→741).
        // Download AC (prio 0) + Notes index.html elsewhere; register other remote files only.
        val names = forceMetaNameSeed(mapping.id).toMutableMap()
        val remotePaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenPaths = mutableSetOf<String>()
        var files = 0
        val filesGate = Mutex()
        var sawRemoteFile = false
        var frontier = listOf(mapping.cloudFolderId to "")
        val downloadThrottle = Semaphore(FORCE_DOWNLOAD_CONCURRENCY)
        val structureThrottle = Semaphore(WALK_REMOTE_CONCURRENCY)
        var matchAnyEnabled: Boolean? = null
        val structureCache = mutableMapOf<String, List<RemoteEntry>>()
        // Soft-fail empty inventory ≠ proven zero children — skip early/final ghost prune under these.
        val inventoryUnknownRels = mutableSetOf<String>()
        // Folder ids already materialized as a wave — breaks remote parent cycles.
        val visitedFolderIds = mutableSetOf<String>()
        // AC getFile deferred while other AC folders remain; non-downloadable files register only.
        val deferredAcRootFiles = mutableListOf<RemoteChangeClassifier.RemoteLocated>()

        // Root listing is hard (empty inventory would prune everything).
        android.util.Log.e(
            "ProtoLinkSync",
            "force pull root list cloudFolderId=${mapping.cloudFolderId.take(8)}…"
        )
        val rootKids = structureThrottle.withPermit {
            listChildrenForceInventory(mapping.cloudFolderId, softFailOnExhausted = false)
        }
        if (rootKids.isEmpty()) {
            throw SyncException("Force download found no remote files in this folder.")
        }
        android.util.Log.e("ProtoLinkSync", "force pull root kids=${rootKids.size}")
        structureCache[mapping.cloudFolderId] = rootKids

        var waveIndex = 0
        while (frontier.isNotEmpty()) {
            waveIndex++
            if (deferredAcRootFiles.isNotEmpty() &&
                frontier.none { forcePathPriority(it.second) == 0 }
            ) {
                val orderedDeferred = deferredAcRootFiles.toList().sortedWith(
                    compareBy<RemoteChangeClassifier.RemoteLocated> { forcePathPriority(it.relativePath) }
                        .thenBy { it.relativePath.lowercase() }
                )
                deferredAcRootFiles.clear()
                coroutineScope {
                    orderedDeferred.map { loc ->
                        async {
                            downloadThrottle.withPermit {
                                val n = materializeForceLocated(mapping, loc, remotePaths)
                                if (n != 0) filesGate.withLock { files += n }
                            }
                        }
                    }.awaitAll()
                }
            }
            // Root wave lists every immediate child (early prune all root ghosts). Deeper waves
            // take same-priority parents (AC-first) up to FORCE_PARENT_BATCH so Films getFile is
            // not blocked on lower-priority Deep GE, while MatchAny still batches peers.
            val pending = frontier.filter { it.first !in visitedFolderIds }
            if (pending.isEmpty()) break
            val waveParents = if (pending.any { it.first == mapping.cloudFolderId }) {
                pending.filter { it.first == mapping.cloudFolderId }
            } else {
                val topPrio = forcePathPriority(pending.first().second)
                pending
                    .filter { forcePathPriority(it.second) == topPrio }
                    .take(FORCE_PARENT_BATCH)
            }
            val waveIds = waveParents.map { it.first }.toSet()
            visitedFolderIds.addAll(waveIds)
            frontier = frontier.filter { it.first !in waveIds }

            val needList = waveParents.map { it.first }
                .filter { it !in structureCache }
                .distinct()
            if (needList.isNotEmpty()) {
                val softFailedIds = mutableSetOf<String>()
                val (listed, nextMatch) = listChildrenForceWaveByParent(
                    needList,
                    structureThrottle,
                    matchAnyEnabled,
                    softFailedIds
                )
                matchAnyEnabled = nextMatch
                for (id in needList) {
                    structureCache[id] = listed[id].orEmpty()
                    if (id in softFailedIds) {
                        val rel = waveParents.first { it.first == id }.second
                        inventoryUnknownRels.add(PathUtil.normalize(rel))
                    }
                }
            }

            val waveKids = waveParents.flatMap { (folderId, relativeParent) ->
                structureCache[folderId].orEmpty().map { kid -> Triple(kid, folderId, relativeParent) }
            }
            val missing = waveKids.map { it.first.id }.filter { id ->
                names[id].isNullOrEmpty()
            }.distinct()
            if (missing.isNotEmpty()) {
                names.putAll(resolveDisplayNamesByIds(missing, seed = names))
            }
            val next = mutableListOf<Pair<String, String>>()
            val waveLocated = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
            for ((kid, folderId, relativeParent) in waveKids) {
                val name = forceResolveChildName(kid.id, kid.isFolder, names) ?: continue
                if (name.isEmpty()) continue
                val rel = PathUtil.join(relativeParent, name)
                val norm = PathUtil.normalize(rel)
                if (!seenPaths.add(norm)) continue
                if (!kid.isFolder) sawRemoteFile = true
                waveLocated.add(
                    RemoteChangeClassifier.RemoteLocated(
                        entry = RemoteEntry(
                            id = kid.id,
                            parentId = folderId,
                            name = name,
                            isFolder = kid.isFolder
                        ),
                        mappingId = mapping.id,
                        relativePath = rel
                    )
                )
                // Live 1.0.76/1.0.77 FORCE_MID_INVENTORY: do not BFS under Agents role-queue
                // task shells (todo|progress|hold|done) — open-queue stop cleared wave16 stall;
                // done shells + evidence still unbounded past 15m. Register shell via materialize.
                if (kid.isFolder &&
                    kid.id !in visitedFolderIds &&
                    !AgentsRoleQueuePaths.shouldSkipFolderMaterialize(rel)
                ) {
                    next.add(kid.id to rel)
                }
            }

            val folders = waveLocated
                .filter { it.entry.isFolder }
                .sortedWith(
                    compareBy<RemoteChangeClassifier.RemoteLocated> { forcePathPriority(it.relativePath) }
                        .thenBy { it.relativePath.count { c -> c == '/' } }
                        .thenBy { it.relativePath.lowercase() }
                )
            // Mkdir without download semaphore — SAF createDirectory must not hold getFile slots.
            coroutineScope {
                folders.map { loc ->
                    async {
                        structureThrottle.withPermit {
                            materializeForceLocated(mapping, loc, remotePaths)
                        }
                    }
                }.awaitAll()
            }
            // Early ghost prune as soon as remote children are named (before file getFile).
            val kidsByParentRel = waveLocated.groupBy {
                PathUtil.parentOf(PathUtil.normalize(it.relativePath))
            }
            android.util.Log.e(
                "ProtoLinkSync",
                "force pull wave=$waveIndex named=${waveLocated.size} parents=${kidsByParentRel.size} " +
                    "beforeEarlyPrune frontier=${frontier.size + next.size}"
            )
            for ((parentRel, kids) in kidsByParentRel) {
                val parentNorm = PathUtil.normalize(parentRel)
                if (parentNorm in inventoryUnknownRels) continue
                pruneImmediateGhosts(
                    mapping,
                    parentRel,
                    kids.map { PathUtil.normalize(it.relativePath) }.toSet()
                )
            }
            // Deepen AC folders before downloading this wave's files (esp. huge root loose set).
            frontier = (frontier + next).sortedWith(
                compareBy<Pair<String, String>> { forcePathPriority(it.second) }
                    .thenBy { it.second.count { c -> c == '/' } }
                    .thenBy { it.second.lowercase() }
            )

            val waveFiles = waveLocated
                .filter { !it.entry.isFolder }
                .sortedWith(
                    compareBy<RemoteChangeClassifier.RemoteLocated> { forcePathPriority(it.relativePath) }
                        .thenBy { it.relativePath.count { c -> c == '/' } }
                        .thenBy { it.relativePath.lowercase() }
                )
            val acFrontierPending = frontier.any { forcePathPriority(it.second) == 0 }
            val downloadNow = mutableListOf<RemoteChangeClassifier.RemoteLocated>()
            for (loc in waveFiles) {
                if (!forceShouldDownloadFile(loc.relativePath)) {
                    // Keep path for end-prune mirror; no getFile (live unbounded blob stall).
                    remotePaths.add(PathUtil.normalize(loc.relativePath))
                    continue
                }
                val parentRel = PathUtil.parentOf(PathUtil.normalize(loc.relativePath))
                // Defer mapping-root AC-prio files only while nested AC folders remain.
                if (acFrontierPending && parentRel.isEmpty() && forcePathPriority(loc.relativePath) > 0) {
                    deferredAcRootFiles.add(loc)
                } else {
                    downloadNow.add(loc)
                }
            }
            coroutineScope {
                downloadNow.map { loc ->
                    async {
                        downloadThrottle.withPermit {
                            val n = materializeForceLocated(mapping, loc, remotePaths)
                            if (n != 0) filesGate.withLock { files += n }
                        }
                    }
                }.awaitAll()
            }
            if (waveIndex == 1 || waveIndex % 5 == 0) {
                android.util.Log.e(
                    "ProtoLinkSync",
                    "force pull wave=$waveIndex files=$files frontier=${frontier.size} remotePaths=${remotePaths.size}"
                )
            }
        }
        if (deferredAcRootFiles.isNotEmpty()) {
            val orderedDeferred = deferredAcRootFiles.sortedWith(
                compareBy<RemoteChangeClassifier.RemoteLocated> { forcePathPriority(it.relativePath) }
                    .thenBy { it.relativePath.lowercase() }
            )
            coroutineScope {
                orderedDeferred.map { loc ->
                    async {
                        downloadThrottle.withPermit {
                            val n = materializeForceLocated(mapping, loc, remotePaths)
                            if (n != 0) filesGate.withLock { files += n }
                        }
                    }
                }.awaitAll()
            }
        }
        if (remotePaths.isEmpty()) {
            throw SyncException("Force download found no remote files in this folder.")
        }
        pruneLocalAbsentFromRemote(mapping, remotePaths, inventoryUnknownRels)
        if (files == 0 && !sawRemoteFile) {
            throw SyncException("Force download processed 0 files (folders only).")
        }
        store.setLastSyncUtc(mapping.id, Instant.now())
        return files
    }

    /**
     * Soft-fail empty GetEntities must not count as proven-empty children for Force prune.
     */
    internal fun isUnderForceInventoryUnknown(
        relativePath: String,
        inventoryUnknownRels: Set<String>
    ): Boolean {
        if (inventoryUnknownRels.isEmpty()) return false
        val norm = PathUtil.normalize(relativePath)
        for (unknown in inventoryUnknownRels) {
            if (unknown.isEmpty()) return true
            if (norm.equals(unknown, ignoreCase = true)) return true
            if (norm.startsWith("$unknown/", ignoreCase = true)) return true
        }
        return false
    }

    /**
     * After a parent’s remote children are named, delete local immediate children absent from
     * that set (wrong-tree ghosts). Soft-fail per item (NFR-SYNC-1). Does not touch deeper
     * paths under still-valid remote folders.
     *
     * Live 1.0.75 FORCE_MID_INVENTORY: never call full-tree [LocalFileSystem.snapshot] here —
     * SAF snapshot hashes every file and stalled after root kids=380 with ghosts already absent.
     * Skip when there are no ghosts; Agents protect uses a scoped listImmediate walk under the
     * ghost folder only.
     */
    private fun pruneImmediateGhosts(
        mapping: SyncMapping,
        parentRelative: String,
        remoteChildPaths: Set<String>
    ) {
        val parentNorm = PathUtil.normalize(parentRelative)
        val remoteNames = remoteChildPaths.map { PathUtil.nameOf(it).lowercase() }.toSet()
        val locals = try {
            fs.listImmediate(mapping.localRootPath, parentNorm)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncRemoteDownload.logMaterializeFailed(
                "ForcePullMapping.EarlyPruneList",
                parentNorm.ifEmpty { "." },
                "-",
                e.message ?: e.javaClass.simpleName
            )
            return
        }
        val ghosts = locals
            .filter { PathUtil.nameOf(it.relativePath).lowercase() !in remoteNames }
            .sortedByDescending { PathUtil.normalize(it.relativePath).count { c -> c == '/' } }
        if (ghosts.isEmpty()) return
        for (entry in ghosts) {
            val path = PathUtil.normalize(entry.relativePath)
            // Path shape alone can protect (Agents/roles/…/todo/…) — do not SAF-walk first.
            if (AgentsRoleQueuePaths.isForcePruneProtected(path, null)) {
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.EarlyPrune.AgentsQueueSkip",
                    path,
                    "-",
                    "skip prune of Agents role open queue path"
                )
                continue
            }
            val protectProbe = if (entry.isFolder) {
                listRelativePathsUnder(mapping, path)
            } else {
                null
            }
            if (AgentsRoleQueuePaths.isForcePruneProtected(path, protectProbe)) {
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.EarlyPrune.AgentsQueueSkip",
                    path,
                    "-",
                    "skip prune of Agents role open queue path"
                )
                continue
            }
            try {
                if (fs.exists(mapping.localRootPath, path)) {
                    fs.delete(mapping.localRootPath, path, entry.isFolder)
                }
                store.delete(mapping.id, path)
                // Drop nested meta under a pruned folder.
                if (entry.isFolder) {
                    val prefix = "$path/"
                    for (meta in store.getAll(mapping.id)) {
                        val m = PathUtil.normalize(meta.relativePath)
                        if (m == path || m.startsWith(prefix)) {
                            store.delete(mapping.id, m)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.EarlyPrune",
                    path,
                    "-",
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * Names/isFolder only via [LocalFileSystem.listImmediate] — no content-hash snapshot.
     * Used for Agents open-queue protect and end-of-Force mirror prune.
     */
    private fun listRelativePathsUnder(mapping: SyncMapping, folderRelative: String): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(PathUtil.normalize(folderRelative))
        while (queue.isNotEmpty()) {
            val rel = queue.removeFirst()
            val kids = try {
                fs.listImmediate(mapping.localRootPath, rel)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.ListUnder",
                    rel.ifEmpty { "." },
                    "-",
                    e.message ?: e.javaClass.simpleName
                )
                continue
            }
            for (kid in kids) {
                val path = PathUtil.normalize(kid.relativePath)
                out.add(path)
                if (kid.isFolder) queue.add(path)
            }
        }
        return out
    }

    /** Full mapping tree via listImmediate (no SAF content hash). */
    private fun listAllRelativeEntries(mapping: SyncMapping): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        val queue = ArrayDeque<String>()
        queue.add("")
        while (queue.isNotEmpty()) {
            val rel = queue.removeFirst()
            val kids = try {
                fs.listImmediate(mapping.localRootPath, rel)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.ListAll",
                    rel.ifEmpty { "." },
                    "-",
                    e.message ?: e.javaClass.simpleName
                )
                continue
            }
            for (kid in kids) {
                out.add(kid)
                if (kid.isFolder) queue.add(PathUtil.normalize(kid.relativePath))
            }
        }
        return out
    }

    /**
     * Create folder or download one Force entry. Soft-fail per item (NFR-SYNC-1).
     * @return 1 when a file was written, else 0
     */
    private suspend fun materializeForceLocated(
        mapping: SyncMapping,
        loc: RemoteChangeClassifier.RemoteLocated,
        remotePaths: MutableSet<String>
    ): Int {
        val path = PathUtil.normalize(loc.relativePath)
        remotePaths.add(path)
        if (loc.entry.isFolder) {
            if (AgentsRoleQueuePaths.shouldSkipFolderMaterialize(path)) {
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.AgentsQueueSkipMkdir",
                    path,
                    loc.entry.id,
                    "skip folder materialize under Agents role queue task shell"
                )
                return 0
            }
            try {
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping",
                    path,
                    loc.entry.id,
                    e.message ?: e.javaClass.simpleName
                )
            }
            return 0
        }
        val bytes = try {
            remote.downloadFile(loc.entry.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // getFile is Response-based (null on 5xx) but keep soft-fail if a future path throws.
            SyncRemoteDownload.logMaterializeFailed(
                "ForcePullMapping",
                path,
                loc.entry.id,
                e.message ?: e.javaClass.simpleName
            )
            return 0
        }
        if (bytes == null) {
            SyncRemoteDownload.logFailed("ForcePullMapping", path, loc.entry.id)
            return 0
        }
        return try {
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
            1
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncRemoteDownload.logMaterializeFailed(
                "ForcePullMapping",
                path,
                loc.entry.id,
                e.message ?: e.javaClass.simpleName
            )
            0
        }
    }

    /**
     * Force download mirror: remove local files/folders (and meta) whose path is not in the
     * remote walk — cloud-absent / Cloud-deleted ghosts leave the device.
     * Paths under soft-fail inventory parents are skipped (unknown ≠ empty).
     * Uses listImmediate walk (no content-hash snapshot) — same Agents protect, far cheaper on SAF.
     */
    private fun pruneLocalAbsentFromRemote(
        mapping: SyncMapping,
        remotePaths: Set<String>,
        inventoryUnknownRels: Set<String>
    ) {
        val snap = listAllRelativeEntries(mapping)
            .sortedByDescending { PathUtil.normalize(it.relativePath).count { c -> c == '/' } }
        val localPaths = snap.map { PathUtil.normalize(it.relativePath) }
        for (entry in snap) {
            val path = PathUtil.normalize(entry.relativePath)
            if (path in remotePaths) continue
            if (isUnderForceInventoryUnknown(path, inventoryUnknownRels)) {
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.Prune.InventoryUnknownSkip",
                    path,
                    "-",
                    "skip prune under soft-fail empty inventory parent"
                )
                continue
            }
            if (AgentsRoleQueuePaths.isForcePruneProtected(path, localPaths)) {
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.Prune.AgentsQueueSkip",
                    path,
                    "-",
                    "skip prune of Agents role open queue path"
                )
                continue
            }
            try {
                if (fs.exists(mapping.localRootPath, path)) {
                    fs.delete(mapping.localRootPath, path, entry.isFolder)
                }
                store.delete(mapping.id, path)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SyncRemoteDownload.logMaterializeFailed(
                    "ForcePullMapping.Prune",
                    path,
                    "-",
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
        for (meta in store.getAll(mapping.id)) {
            val path = PathUtil.normalize(meta.relativePath)
            if (path in remotePaths) continue
            if (isUnderForceInventoryUnknown(path, inventoryUnknownRels)) continue
            if (AgentsRoleQueuePaths.isForcePruneProtected(path, localPaths)) continue
            store.delete(mapping.id, path)
        }
    }
}
