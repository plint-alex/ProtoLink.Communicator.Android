package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes

/**
 * Pure helpers for Communicator Cloud/Photos persistent folder tree (AT-PC1 / AT-PC8).
 * Parity with Windows CloudFolderTree.
 */
object CloudFolderTree {
    const val DEFAULT_MAX_FOLDERS = 200
    const val DEFAULT_MAX_DEPTH = 12

    data class Node(val id: String, val name: String)

    data class FlatRow(
        val id: String,
        val name: String,
        val depth: Int,
        val selected: Boolean
    )

    fun isFolderTreeCode(code: String?): Boolean =
        code == CloudCodes.CLOUD_FOLDER || code == CloudCodes.CLOUD_ROOT

    /**
     * Tree root from breadcrumb: prefer CloudRoot id when present; else first non-virtual crumb;
     * when [photosLibraryId] is on the trail, prefer that (Photos chrome).
     */
    fun pickTreeRootId(
        crumbs: List<Pair<String, String?>>,
        photosLibraryId: String?,
        isVirtual: (String) -> Boolean
    ): String? {
        if (crumbs.isEmpty()) return null

        val lib = photosLibraryId?.takeIf { it.isNotBlank() }
        if (lib != null) {
            for ((id, _) in crumbs) {
                if (id.equals(lib, ignoreCase = true)) return lib
            }
        }

        for ((id, code) in crumbs) {
            if (isVirtual(id)) continue
            if (code == CloudCodes.CLOUD_ROOT) return id
        }

        for ((id, _) in crumbs) {
            if (!isVirtual(id)) return id
        }
        return null
    }

    /** Highlight id: file mode uses containing folder; folder uses open entity. */
    fun selectedFolderId(
        isFileOpen: Boolean,
        openEntityId: String?,
        parentFolderId: String?
    ): String? = if (isFileOpen) parentFolderId else openEntityId

    fun flatten(
        root: Node?,
        childrenByParent: Map<String, List<Node>>,
        selectedId: String?,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): List<FlatRow> {
        if (root == null || root.id.isBlank()) return emptyList()
        val out = ArrayList<FlatRow>()
        fun walk(id: String, name: String, depth: Int) {
            if (depth > maxDepth) return
            val selected = selectedId != null && id.equals(selectedId, ignoreCase = true)
            out.add(FlatRow(id, name, depth, selected))
            if (depth >= maxDepth) return
            val kids = childrenByParent[id] ?: return
            for (k in kids) walk(k.id, k.name, depth + 1)
        }
        walk(root.id, root.name, 0)
        return out
    }
}
