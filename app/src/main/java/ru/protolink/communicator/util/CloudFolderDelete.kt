package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.sync.ports.LocalFileSystem

/**
 * E10 Cloud folder/file delete — confirm copy, CanWrite gate helpers, recursive DeleteEntity
 * order, and mapped local-mirror cleanup (NFR-SYNC-1 soft-fail). Mirrors Windows
 * CloudViewModel.DeleteAsync / DeleteFolderRecursiveAsync / CloudMappingMirrorPaths.
 */
object CloudFolderDelete {
    data class ChildRef(val id: String, val code: String?)

    data class MirrorTarget(
        val rootUri: String,
        /** Empty when the deleted item is the mapped root itself. */
        val relativePath: String,
        val isMappedRoot: Boolean
    )

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
     * Depth-first: delete CloudFile children, recurse CloudFolder children, then delete [folderId].
     * Matches Windows DeleteFolderRecursiveAsync (Api has no cascade).
     * [deletedIds] collects every successfully deleted remote id (for sync-meta purge / Soft Ready tombstones).
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
            val code = c.code.orEmpty()
            when {
                code.equals(CloudCodes.CLOUD_FILE, ignoreCase = true) -> {
                    deleteEntity(c.id)
                    deletedIds?.add(c.id)
                }
                code.equals(CloudCodes.CLOUD_FOLDER, ignoreCase = true) -> {
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
