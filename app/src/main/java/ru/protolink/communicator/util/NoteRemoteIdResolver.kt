package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.sync.engine.PathUtil
import ru.protolink.communicator.sync.model.SyncItemMeta

/**
 * E2 note identity: Notes root + relative path → mapped Cloud folder RemoteId.
 * Parity with Windows NoteRemoteIdResolver (AC-NS-1 / AT-S47).
 */
object NoteRemoteIdResolver {
    fun mappingIdOf(cloudFolderId: String): String =
        cloudFolderId.replace("-", "").lowercase()

    /**
     * Resolve Cloud remote id for a Notes tree node. Null when unmapped / not synced.
     */
    fun resolve(
        notesRootUri: String?,
        noteRelativePath: String?,
        mappings: List<CloudSyncMappingDto>,
        itemsForMapping: (mappingId: String) -> List<SyncItemMeta>
    ): String? {
        val plan = planResolve(notesRootUri, noteRelativePath, mappings) ?: return null
        if (plan.underMapping.isEmpty()) {
            return plan.cloudFolderId.takeIf { it.isNotBlank() }
        }
        val items = itemsForMapping(plan.mappingId)
        return findFolderRemoteId(items, plan.mappingId, plan.underMapping)
    }

    /** Pure resolve when items for the chosen mapping are already loaded. */
    fun resolveFrom(
        notesRootUri: String?,
        noteRelativePath: String?,
        mappings: List<CloudSyncMappingDto>,
        items: List<SyncItemMeta>
    ): String? {
        val plan = planResolve(notesRootUri, noteRelativePath, mappings) ?: return null
        if (plan.underMapping.isEmpty()) {
            return plan.cloudFolderId.takeIf { it.isNotBlank() }
        }
        return findFolderRemoteId(items, plan.mappingId, plan.underMapping)
    }

    internal data class ResolvePlan(
        val cloudFolderId: String,
        val mappingId: String,
        /** Relative path under the mapping root (empty = mapping root itself). */
        val underMapping: String
    )

    internal fun planResolve(
        notesRootUri: String?,
        noteRelativePath: String?,
        mappings: List<CloudSyncMappingDto>
    ): ResolvePlan? {
        val notesRoot = notesRootUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val noteRel = PathUtil.normalize(noteRelativePath.orEmpty())
        if (mappings.isEmpty()) return null

        // Mapping covers Notes root (equal or Notes nested under mapped folder).
        val covering = findBestCovering(notesRoot, mappings)
        if (covering != null) {
            val prefix = LocalUriCoverage.relativeUnder(notesRoot, covering.localPath) ?: return null
            val under = joinRel(prefix, noteRel)
            return ResolvePlan(
                cloudFolderId = covering.cloudFolderId,
                mappingId = mappingIdOf(covering.cloudFolderId),
                underMapping = under
            )
        }

        // Mapping nested under Notes: note must lie under that mapped subfolder.
        val nested = findBestNestedUnderNotes(notesRoot, noteRel, mappings) ?: return null
        return ResolvePlan(
            cloudFolderId = nested.cloudFolderId,
            mappingId = mappingIdOf(nested.cloudFolderId),
            underMapping = nested.underMapping
        )
    }

    private fun findBestCovering(
        notesRoot: String,
        mappings: List<CloudSyncMappingDto>
    ): CloudSyncMappingDto? {
        var best: CloudSyncMappingDto? = null
        var bestLen = -1
        for (m in mappings) {
            val root = m.localPath.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!LocalUriCoverage.isUnderOrEqual(notesRoot, root)) continue
            val len = LocalUriCoverage.tryNormalize(root)?.length ?: continue
            if (len > bestLen) {
                best = m
                bestLen = len
            }
        }
        return best
    }

    private data class NestedHit(val cloudFolderId: String, val underMapping: String)

    private fun findBestNestedUnderNotes(
        notesRoot: String,
        noteRel: String,
        mappings: List<CloudSyncMappingDto>
    ): NestedHit? {
        var best: NestedHit? = null
        var bestPrefixLen = -1
        for (m in mappings) {
            val mapRoot = m.localPath.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!LocalUriCoverage.isUnderOrEqual(mapRoot, notesRoot)) continue
            val mapUnderNotes = LocalUriCoverage.relativeUnder(mapRoot, notesRoot) ?: continue
            val prefix = PathUtil.normalize(mapUnderNotes)
            if (prefix.isEmpty()) continue // equal → handled by covering branch
            val underNote =
                if (noteRel.isEmpty()) null
                else if (noteRel == prefix) ""
                else if (noteRel.startsWith("$prefix/")) noteRel.substring(prefix.length + 1)
                else null
            if (underNote == null) continue
            if (prefix.length > bestPrefixLen) {
                best = NestedHit(m.cloudFolderId, underNote)
                bestPrefixLen = prefix.length
            }
        }
        return best
    }

    private fun joinRel(prefix: String, noteRel: String): String {
        val p = PathUtil.normalize(prefix)
        val n = PathUtil.normalize(noteRel)
        return when {
            p.isEmpty() -> n
            n.isEmpty() -> p
            else -> "$p/$n"
        }
    }

    private fun findFolderRemoteId(
        items: List<SyncItemMeta>,
        mappingId: String,
        underMapping: String
    ): String? {
        val want = PathUtil.normalize(underMapping)
        for (item in items) {
            if (!item.isFolder) continue
            if (!item.mappingId.equals(mappingId, ignoreCase = true)) continue
            if (PathUtil.normalize(item.relativePath) != want) continue
            return item.remoteId.takeIf { it.isNotBlank() }
        }
        return null
    }
}
