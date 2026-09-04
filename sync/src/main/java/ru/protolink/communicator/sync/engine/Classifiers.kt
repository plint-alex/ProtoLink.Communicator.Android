package ru.protolink.communicator.sync.engine

import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.LocalChange
import ru.protolink.communicator.sync.model.RemoteChange
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.model.SyncItemMeta

object PathUtil {
    fun normalize(path: String): String =
        path.replace('\\', '/').trim('/').lowercase()

    fun parentOf(relativePath: String): String {
        val n = normalize(relativePath)
        val i = n.lastIndexOf('/')
        return if (i < 0) "" else n.substring(0, i)
    }

    fun nameOf(relativePath: String): String {
        val n = normalize(relativePath)
        val i = n.lastIndexOf('/')
        return if (i < 0) n else n.substring(i + 1)
    }

    fun join(parent: String, name: String): String {
        val p = normalize(parent)
        val n = name.replace('\\', '/').trim('/')
        return if (p.isEmpty()) n.lowercase() else "$p/${n.lowercase()}"
    }
}

/**
 * Classifies local FS vs metadata store (Phase L).
 * Rename requires a uniquely matching size among unpaired entries.
 */
class LocalChangeClassifier {
    fun classify(fs: List<FsEntry>, store: List<SyncItemMeta>): List<LocalChange> {
        val fsByPath = fs.associateBy { PathUtil.normalize(it.relativePath) }
        val storeByPath = store.associateBy { PathUtil.normalize(it.relativePath) }

        val onlyFs = fsByPath.keys - storeByPath.keys
        val onlyStore = storeByPath.keys - fsByPath.keys
        val both = fsByPath.keys.intersect(storeByPath.keys)

        val unpairedFs = onlyFs.map { fsByPath.getValue(it) }.toMutableList()
        val unpairedStore = onlyStore.map { storeByPath.getValue(it) }.toMutableList()

        val changes = mutableListOf<LocalChange>()
        val usedFs = mutableSetOf<String>()
        val usedStore = mutableSetOf<String>()

        // Size frequency across unpaired for uniqueness
        val sizeCounts = mutableMapOf<Long, Int>()
        for (e in unpairedFs) sizeCounts[e.sizeBytes] = (sizeCounts[e.sizeBytes] ?: 0) + 1
        for (m in unpairedStore) sizeCounts[m.sizeBytes] = (sizeCounts[m.sizeBytes] ?: 0) + 1

        for (missing in unpairedStore.toList()) {
            if (usedStore.contains(PathUtil.normalize(missing.relativePath))) continue
            // Empty folders all size 0 → not unique when more than one
            val matches = unpairedFs.filter {
                !usedFs.contains(PathUtil.normalize(it.relativePath)) &&
                    it.isFolder == missing.isFolder &&
                    it.sizeBytes == missing.sizeBytes
            }
            val unique = matches.size == 1 && (sizeCounts[missing.sizeBytes] ?: 0) == 2
            // unique means exactly one fs and one store share that size (count==2)
            if (unique) {
                val neu = matches.first()
                usedFs.add(PathUtil.normalize(neu.relativePath))
                usedStore.add(PathUtil.normalize(missing.relativePath))
                val newParentPath = PathUtil.parentOf(neu.relativePath)
                // parent remote id resolved later by engine; placeholder = old parent until resolved
                changes.add(
                    LocalChange.Renamed(
                        meta = missing,
                        newRelativePath = PathUtil.normalize(neu.relativePath),
                        newParentRemoteId = missing.parentRemoteId,
                        newName = PathUtil.nameOf(neu.relativePath)
                    )
                )
            }
        }

        for (path in onlyFs) {
            if (usedFs.contains(path)) continue
            changes.add(LocalChange.Added(fsByPath.getValue(path)))
        }
        for (path in onlyStore) {
            if (usedStore.contains(path)) continue
            changes.add(LocalChange.Removed(storeByPath.getValue(path)))
        }
        for (path in both) {
            val f = fsByPath.getValue(path)
            val s = storeByPath.getValue(path)
            if (f.isFolder != s.isFolder) continue
            if (f.sizeBytes != s.sizeBytes) {
                changes.add(LocalChange.Updated(s, f.sizeBytes))
            }
        }
        return changes
    }
}

/**
 * Classifies remote tree vs metadata store (Phase R).
 * [remoteById] is the full remote snapshot under all mapped roots (id → entry + absolute relative path within its mapping).
 * [dirtyLocalPaths] paths already changed this cycle — skip remote overwrite/delete for those.
 */
class RemoteChangeClassifier {
    data class RemoteLocated(
        val entry: RemoteEntry,
        val mappingId: String,
        val relativePath: String
    )

    fun classify(
        store: List<SyncItemMeta>,
        remoteLocated: List<RemoteLocated>,
        allStoreRemoteIds: Set<String>,
        dirtyLocalPaths: Set<String>
    ): List<RemoteChange> {
        val remoteById = remoteLocated.associateBy { it.entry.id }
        val storeById = store.associateBy { it.remoteId }
        val changes = mutableListOf<RemoteChange>()

        for (meta in store) {
            val dirty = dirtyLocalPaths.contains(PathUtil.normalize(meta.relativePath))
            val remote = remoteById[meta.remoteId]
            if (remote == null) {
                // Not in any mapped remote tree
                if (!dirty) changes.add(RemoteChange.Removed(meta))
                continue
            }
            if (remote.mappingId != meta.mappingId ||
                PathUtil.normalize(remote.relativePath) != PathUtil.normalize(meta.relativePath)
            ) {
                if (!dirty) {
                    changes.add(
                        RemoteChange.Renamed(
                            meta = meta,
                            remote = remote.entry,
                            newRelativePath = PathUtil.normalize(remote.relativePath)
                        )
                    )
                }
                continue
            }
            val remoteSize = remote.entry.sizeBytes
            val updateNewer = remote.entry.updateTime != null &&
                meta.remoteUpdateTime != null &&
                remote.entry.updateTime!! > meta.remoteUpdateTime
            val sizeDiffers = remoteSize != null && remoteSize != meta.sizeBytes
            if (!dirty && !meta.isFolder && (sizeDiffers || updateNewer) &&
                // local not dirty means FS size still equals store
                true
            ) {
                // Engine verifies FS size == store before download
                changes.add(RemoteChange.Updated(meta, remote.entry))
            }
        }

        for (located in remoteLocated) {
            if (located.entry.id in allStoreRemoteIds) continue
            // Only add into the mapping where it was found
            changes.add(RemoteChange.Added(located.entry, PathUtil.normalize(located.relativePath)))
        }

        return changes
    }
}
