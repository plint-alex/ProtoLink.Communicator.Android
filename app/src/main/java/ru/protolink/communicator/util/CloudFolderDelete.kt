package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudEntityClassify
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.sync.ports.LocalFileSystem

/**
 * E10 Cloud folder/file delete — confirm copy, CanWrite gate helpers, single-root
 * DeleteEntity (Api cascade), local known-subtree collect for Soft Ready purge, and
 * mapped local-mirror cleanup (NFR-SYNC-1 soft-fail). Recursive deleteFolderRecursive
 * is offline unit-test only. Mirrors Windows CloudFolderDelete / CloudViewModel.DeleteAsync.
 */
object CloudFolderDelete {
    data class ChildRef(val id: String, val code: String?, val typeEntityId: String? = null)

    data class MirrorTarget(
        val rootUri: String,
        /** Empty when the deleted item is the mapped root itself. */
        val relativePath: String,
        val isMappedRoot: Boolean
    )

    enum class SharedDeleteChoice {
        Cancel,
        ForMeOnly,
        ForAll
    }

    fun canDeleteItem(
        isVirtualEntry: Boolean,
        itemId: String,
        breadcrumbIds: Iterable<String>,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean {
        if (isVirtualEntry || CloudVirtualNodes.isVirtual(itemId)) return false
        return CloudSharedScope.allowsWriteUnderSharedScope(
            breadcrumbIds,
            itemId,
            shareRootCanWrite
        )
    }

    /**
     * E12: me/all for SWM/SBM share roots, known outbound roots, or grant probe — not nested SWM children.
     */
    fun isSharedDeleteTarget(
        isSwmShareRoot: Boolean,
        isKnownOutboundShareRoot: Boolean,
        hasExternalGrants: Boolean
    ): Boolean = isSwmShareRoot || isKnownOutboundShareRoot || hasExternalGrants

    /**
     * AT-SFD-2: ListGrants on an unshared owner folder still returns the caller's own people grant.
     * Count only principals whose permissionForId differs from the caller.
     */
    fun hasExternalGrants(
        permissionForIds: Iterable<String?>?,
        callerId: String?
    ): Boolean {
        val me = callerId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (permissionForIds == null) return false
        for (raw in permissionForIds) {
            val id = raw?.trim()?.lowercase().orEmpty()
            if (id.isNotEmpty() && id != me) return true
        }
        return false
    }

    /**
     * Leave only for inbound SWM. Known outbound must Detach — creator Leave is Left=false
     * / MainParent no-op (AT-SFD-4).
     */
    fun preferLeaveForMeOnly(
        isSwmShareRoot: Boolean,
        isKnownOutboundShareRoot: Boolean = false
    ): Boolean = isSwmShareRoot && !isKnownOutboundShareRoot

    fun preferDetachForMeOnly(
        isSwmShareRoot: Boolean,
        isInSharedWithMeScope: Boolean,
        isKnownOutboundShareRoot: Boolean
    ): Boolean = !preferLeaveForMeOnly(
        isSwmShareRoot = isSwmShareRoot && isInSharedWithMeScope,
        isKnownOutboundShareRoot = isKnownOutboundShareRoot
    )

    fun deleteMenu(lang: String?): String =
        if (isRu(lang)) "Удалить" else "Delete"

    fun confirmTitle(lang: String?): String =
        if (isRu(lang)) "Удалить" else "Delete"

    fun confirmBody(lang: String?, name: String, isFolder: Boolean): String {
        val safe = name.ifBlank { "—" }
        return if (isFolder) {
            if (isRu(lang))
                "Удалить папку «$safe» и всё её содержимое?"
            else
                "Delete folder \"$safe\" and all its contents?"
        } else {
            if (isRu(lang))
                "Удалить «$safe»?"
            else
                "Delete \"$safe\"?"
        }
    }

    fun sharedConfirmBody(lang: String?, name: String, isFolder: Boolean): String {
        val safe = name.ifBlank { "—" }
        return if (isRu(lang)) {
            if (isFolder)
                "«$safe» доступна нескольким людям. Удалить только у себя или для всех?"
            else
                "«$safe» доступен нескольким людям. Удалить только у себя или для всех?"
        } else {
            if (isFolder)
                "\"$safe\" is shared. Remove it for you only, or delete it for everyone?"
            else
                "\"$safe\" is shared. Remove it for you only, or delete it for everyone?"
        }
    }

    fun forMeOnly(lang: String?): String =
        if (isRu(lang)) "Только для меня" else "For me only"

    fun forAllUsers(lang: String?): String =
        if (isRu(lang)) "Для всех" else "For all users"

    fun detachedStatus(lang: String?, name: String): String {
        val safe = name.ifBlank { "—" }
        return if (isRu(lang))
            "Убрано из Моего облака: «$safe»"
        else
            "Removed from My Cloud: \"$safe\""
    }

    /** AT-SFD-4: Api detached=false — do not paint success. */
    fun detachFailedStatus(lang: String?, name: String): String {
        val safe = name.ifBlank { "—" }
        return if (isRu(lang))
            "Не удалось убрать из Моего облака: «$safe»"
        else
            "Could not remove from My Cloud: \"$safe\""
    }

    fun permissionDenied(lang: String?): String =
        if (isRu(lang))
            "Нет права удалить этот элемент."
        else
            "You don't have permission to delete this item."

    fun deletedStatus(lang: String?, name: String): String {
        val safe = name.ifBlank { "—" }
        return if (isRu(lang)) "Удалено «$safe»" else "Deleted \"$safe\""
    }

    /**
     * Resolve SAF tree URI + relative path for a listed Cloud item under the current breadcrumb.
     * Null when the listing is not under a mapped folder.
     */
    fun resolveMirrorTarget(
        itemId: String,
        itemName: String,
        breadcrumb: List<Pair<String, String>>,
        mappings: List<CloudSyncMappingDto>
    ): MirrorTarget? {
        val mappedRoot = mappings.firstOrNull { it.cloudFolderId.equals(itemId, ignoreCase = true) }
        if (mappedRoot != null && mappedRoot.localPath.isNotBlank()) {
            return MirrorTarget(
                rootUri = mappedRoot.localPath,
                relativePath = "",
                isMappedRoot = true
            )
        }

        var mapIndex = -1
        var mapping: CloudSyncMappingDto? = null
        for (i in breadcrumb.indices.reversed()) {
            val id = breadcrumb[i].first
            val m = mappings.firstOrNull { it.cloudFolderId.equals(id, ignoreCase = true) }
            if (m != null && m.localPath.isNotBlank()) {
                mapping = m
                mapIndex = i
                break
            }
        }
        if (mapping == null) return null

        val segments = ArrayList<String>()
        for (j in (mapIndex + 1) until breadcrumb.size) {
            segments.add(breadcrumb[j].second)
        }
        segments.add(itemName)
        return MirrorTarget(
            rootUri = mapping.localPath,
            relativePath = segments.joinToString("/").trim('/'),
            isMappedRoot = false
        )
    }

    /**
     * NFR-SYNC-1: missing path / IO soft-fails (returns false) so Cloud delete is not aborted.
     * Mapped root: clears children under the tree grant; does not require deleting the grant root.
     */
    fun softDeleteLocalMirror(
        localFs: LocalFileSystem,
        target: MirrorTarget?,
        isFolder: Boolean
    ): Boolean {
        if (target == null || target.rootUri.isBlank()) return true
        return try {
            if (target.isMappedRoot || target.relativePath.isBlank()) {
                val entries = runCatching { localFs.snapshot(target.rootUri) }.getOrDefault(emptyList())
                var allOk = true
                for (e in entries) {
                    val ok = runCatching {
                        localFs.delete(target.rootUri, e.relativePath, e.isFolder)
                    }.isSuccess
                    if (!ok) allOk = false
                }
                allOk
            } else {
                val exists = runCatching {
                    localFs.exists(target.rootUri, target.relativePath)
                }.getOrDefault(false)
                if (!exists) return true
                localFs.delete(target.rootUri, target.relativePath, isFolder)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Production delete: one DeleteEntity on [rootId]; Api cascade soft-hides the Cloud subtree.
     * Adds [rootId] to [deletedIds]. Fail-closed: if delete throws, caller must not pretend gone.
     */
    suspend fun deleteSingleRoot(
        rootId: String,
        deleteEntity: suspend (String) -> Unit,
        deletedIds: MutableCollection<String>? = null
    ) {
        deleteEntity(rootId)
        deletedIds?.add(rootId)
    }

    /**
     * BFS over *local* known children (folder-tree cache / sync-meta / listing) — no HTTP.
     * Collects folder + file ids under [rootId] into [into] (includes root). Used after
     * single-root DeleteEntity to purge Soft Ready / tombstones without re-walking the Api.
     */
    fun collectKnownSubtreeIds(
        rootId: String,
        knownChildren: (String) -> List<ChildRef>,
        into: MutableCollection<String>
    ) {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()

        fun remember(id: String) {
            val key = normalizeRemoteId(id)
            if (!seen.add(key)) return
            into.add(id)
        }

        remember(rootId)
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            val kids = runCatching { knownChildren(parent) }.getOrDefault(emptyList())
            for (c in kids) {
                if (c.id.isBlank() || c.id.equals(parent, ignoreCase = true)) continue
                val key = normalizeRemoteId(c.id)
                if (!seen.add(key)) continue
                into.add(c.id)
                if (CloudEntityClassify.isFolder(c.typeEntityId, c.code)) {
                    queue.add(c.id)
                }
            }
        }
    }

    /**
     * Offline unit-test helper only — leaf-first recursive DeleteEntity.
     * Do **not** call from UI delete (Api cascade + [deleteSingleRoot] is production; N+1 is too slow).
     */
    suspend fun deleteFolderRecursive(
        folderId: String,
        listChildren: suspend (String) -> List<ChildRef>,
        deleteEntity: suspend (String) -> Unit,
        deletedIds: MutableCollection<String>? = null
    ) {
        val children = listChildren(folderId)
        for (c in children) {
            if (c.id.equals(folderId, ignoreCase = true)) continue
            when {
                CloudEntityClassify.isFile(c.typeEntityId, c.code) -> {
                    deleteEntity(c.id)
                    deletedIds?.add(c.id)
                }
                CloudEntityClassify.isFolder(c.typeEntityId, c.code) -> {
                    deleteFolderRecursive(c.id, listChildren, deleteEntity, deletedIds)
                }
            }
        }
        deleteEntity(folderId)
        deletedIds?.add(folderId)
    }

    /** Normalize Cloud entity ids for tombstone / meta purge (dash-insensitive). */
    fun normalizeRemoteId(id: String): String =
        id.replace("-", "").lowercase()

    private fun isRu(lang: String?): Boolean =
        lang?.startsWith("ru", ignoreCase = true) == true
}
