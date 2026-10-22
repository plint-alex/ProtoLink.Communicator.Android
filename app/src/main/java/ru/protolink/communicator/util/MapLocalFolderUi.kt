package ru.protolink.communicator.util

/**
 * Pure visibility rules for E6 hub CTA (AT-MAP-1 / AT-MAP-5).
 */
object MapLocalFolderUi {
    fun showHubCta(
        authenticated: Boolean,
        currentFolderId: String?,
        breadcrumbIds: Iterable<String>,
        mappedFolderIds: Set<String>,
        shareRootCanWrite: Map<String, Boolean>
    ): Boolean {
        if (!authenticated) return false
        if (currentFolderId.isNullOrBlank()) return false
        if (currentFolderId.equals(CloudVirtualNodes.SHARED_WITH_ME_ID, ignoreCase = true)) return false
        if (CloudSharedScope.showSharedBrowseOnlyHint(currentFolderId, breadcrumbIds, shareRootCanWrite)) {
            return false
        }
        if (mappedFolderIds.any { it.equals(currentFolderId, ignoreCase = true) }) return false
        if (breadcrumbIds.any { id -> mappedFolderIds.any { it.equals(id, ignoreCase = true) } }) {
            return false
        }
        val isHub = currentFolderId.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true)
        val canMutate = !CloudVirtualNodes.isVirtual(currentFolderId)
        return isHub || canMutate
    }

    fun showNotesNudge(notesRootUri: String?, mappingLocalRoots: Iterable<String?>): Boolean {
        if (notesRootUri.isNullOrBlank()) return false
        return !LocalUriCoverage.isCoveredByAnyMappingRoot(notesRootUri, mappingLocalRoots)
    }
}
