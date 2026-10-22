package ru.protolink.communicator.util

/**
 * Shared-with-me navigation helpers (AT-S42 refined): Map allowed only when the active share root is writable.
 */
object CloudSharedScope {
    fun isInSharedWithMeScope(breadcrumbIds: Iterable<String>): Boolean =
        breadcrumbIds.any { it.equals(CloudVirtualNodes.SHARED_WITH_ME_ID, ignoreCase = true) }

    /**
     * First breadcrumb id after the Shared-with-me virtual node, or null when still on the listing / not in scope.
     */
    fun tryGetActiveShareRootId(breadcrumbIds: Iterable<String>): String? {
        var seenShared = false
        for (id in breadcrumbIds) {
            if (!seenShared) {
                if (id.equals(CloudVirtualNodes.SHARED_WITH_ME_ID, ignoreCase = true)) {
                    seenShared = true
                }
                continue
            }
            return id
        }
        return null
    }

    /**
     * Whether the share root that owns [folderId] (or the active breadcrumb root) has CanWrite.
     * When still on the Shared-with-me listing, [folderId] itself is treated as the share root.
     * Keys in [shareRootCanWrite] are expected lowercased.
     */
    fun isShareRootWritable(
        breadcrumbIds: Iterable<String>,
        folderId: String,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean {
        val rootId = tryGetActiveShareRootId(breadcrumbIds) ?: folderId
        return shareRootCanWrite[rootId.lowercase()] == true
    }

    /** Outside Shared with me always true; inside, only when the active share root is writable. */
    fun allowsMapUnderSharedScope(
        breadcrumbIds: Iterable<String>,
        folderId: String,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean =
        !isInSharedWithMeScope(breadcrumbIds) ||
            isShareRootWritable(breadcrumbIds, folderId, shareRootCanWrite)

    fun showSharedBrowseOnlyHint(
        currentFolderId: String?,
        breadcrumbIds: Iterable<String>,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean {
        if (currentFolderId.isNullOrBlank()) return false
        if (CloudVirtualNodes.isVirtual(currentFolderId)) return false
        if (!isInSharedWithMeScope(breadcrumbIds)) return false
        return !isShareRootWritable(breadcrumbIds, currentFolderId, shareRootCanWrite)
    }

    /**
     * Whether the Cloud toolbar may offer Map for the current folder.
     * Hidden on virtual hub nodes; under Shared with me only when the active share root has CanWrite.
     */
    fun canMapCurrentFolder(
        currentFolderId: String?,
        breadcrumbIds: Iterable<String>,
        mappedFolderIds: Set<String>,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean {
        if (currentFolderId.isNullOrBlank()) return false
        if (CloudVirtualNodes.isVirtual(currentFolderId)) return false
        if (!allowsMapUnderSharedScope(breadcrumbIds, currentFolderId, shareRootCanWrite)) {
            return false
        }
        if (mappedFolderIds.any { it.equals(currentFolderId, ignoreCase = true) }) return false
        if (breadcrumbIds.any { id -> mappedFolderIds.any { it.equals(id, ignoreCase = true) } }) {
            return false
        }
        return true
    }
}
