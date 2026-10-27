package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes

/**
 * Pure helpers for Communicator Cloud/Photos persistent folder tree (AT-PC1 / AT-PC8 / E9b).
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

    /** Visible tree row with expand state (E9b / AT-CE9…CE11). */
    data class VisibleRow(
        val id: String,
        val name: String,
        val depth: Int,
        val selected: Boolean,
        val isExpanded: Boolean,
        val canExpand: Boolean,
        val isLoading: Boolean
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

    /**
     * Full DFS flatten (legacy / tests). Prefer [projectVisible] for E9b UI.
     */
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

    /**
     * Project only expanded paths. Unloaded parents stay expandable (lazy).
     * Loaded empty parents are leaves.
     */
    fun projectVisible(
        root: Node?,
        childrenByParent: Map<String, List<Node>>,
        expandedIds: Set<String>,
        loadedParentIds: Set<String>,
        selectedId: String?,
        loadingIds: Set<String> = emptySet(),
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): List<VisibleRow> {
        if (root == null || root.id.isBlank()) return emptyList()
        val out = ArrayList<VisibleRow>()
        fun walk(id: String, name: String, depth: Int) {
            if (depth > maxDepth) return
            val loaded = loadedParentIds.any { it.equals(id, ignoreCase = true) }
            val kids = childrenByParent.entries
                .firstOrNull { it.key.equals(id, ignoreCase = true) }
                ?.value
            val hasKids = kids != null && kids.isNotEmpty()
            val canExpand = !loaded || hasKids
            val isExpanded = canExpand && expandedIds.any { it.equals(id, ignoreCase = true) }
            val isLoading = loadingIds.any { it.equals(id, ignoreCase = true) }
            val selected = selectedId != null && id.equals(selectedId, ignoreCase = true)
            out.add(
                VisibleRow(
                    id = id,
                    name = name,
                    depth = depth,
                    selected = selected,
                    isExpanded = isExpanded,
                    canExpand = canExpand,
                    isLoading = isLoading
                )
            )
            if (!isExpanded || depth >= maxDepth || kids == null) return
            for (k in kids) walk(k.id, k.name, depth + 1)
        }
        walk(root.id, root.name, 0)
        return out
    }

    /** Remove [folderId] and all known descendants from the expanded set. */
    fun collapseBranch(
        folderId: String,
        childrenByParent: Map<String, List<Node>>,
        expandedIds: MutableSet<String>
    ) {
        val key = expandedIds.firstOrNull { it.equals(folderId, ignoreCase = true) } ?: return
        expandedIds.remove(key)
        val kids = childrenByParent.entries
            .firstOrNull { it.key.equals(folderId, ignoreCase = true) }
            ?.value
            ?: return
        for (k in kids) collapseBranch(k.id, childrenByParent, expandedIds)
    }
}
