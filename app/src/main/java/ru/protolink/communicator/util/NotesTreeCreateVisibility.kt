package ru.protolink.communicator.util

import android.net.Uri
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SafTreeLister

/**
 * After Notes create: keep the new page visible in the flattened tree.
 * SAF re-scan can lag or leave a parent collapsed; merge + expand must not wait on a second
 * full DocumentsContract walk before [notesTreeRows] show the leaf.
 */
object NotesTreeCreateVisibility {

    data class CreatedLeaf(
        val name: String,
        val relativePath: String,
        val documentId: String,
        val safDocumentId: String,
        val folderUri: String,
        val indexUri: String?
    )

    fun normalizeRelative(relativePath: String): String =
        relativePath.replace('\\', '/').trim('/')

    /**
     * Empty root node from the SAF tree URI — used when [notesRootCache] is still null
     * because the initial DocumentsContract walk has not finished (cold hub / large tree).
     */
    fun minimalRoot(treeUri: Uri, rootName: String = "Notes"): NotesTreeBuilder.Node {
        val rootId = SafTreeLister.treeDocumentId(treeUri)
        val rootUri = SafTreeLister.documentUri(treeUri, rootId)
        return NotesTreeBuilder.Node(
            documentId = rootUri.toString(),
            safDocumentId = rootId,
            name = rootName,
            relativePath = "",
            folderUri = rootUri,
            indexUri = null,
            children = emptyList()
        )
    }

    /**
     * Optimistic merge when cache may be null: seed [minimalRoot] if needed, insert leaf
     * (stubbing missing ancestors via guessed SAF ids), expand path for flatten.
     */
    fun ensureCreatedVisibleSeeding(
        prior: NotesTreeBuilder.Node?,
        treeUri: Uri,
        leaf: CreatedLeaf,
        expandedIds: Set<String>,
        rootName: String = "Notes"
    ): Pair<NotesTreeBuilder.Node, Set<String>> {
        val base = prior ?: minimalRoot(treeUri, rootName)
        val withAncestors = ensureAncestorStubs(base, leaf, treeUri)
        return ensureCreatedVisible(withAncestors, leaf, expandedIds)
    }

    /** Insert missing parent folders so [insertCreatedLeaf] can attach [leaf] under a seed root. */
    fun ensureAncestorStubs(
        root: NotesTreeBuilder.Node,
        leaf: CreatedLeaf,
        treeUri: Uri
    ): NotesTreeBuilder.Node {
        val rel = normalizeRelative(leaf.relativePath)
        val parentParts = rel.split('/').filter { it.isNotEmpty() }.dropLast(1)
        if (parentParts.isEmpty()) return root
        return insertAncestorChain(root, parentParts, 0, treeUri) ?: root
    }

    private fun insertAncestorChain(
        node: NotesTreeBuilder.Node,
        parentParts: List<String>,
        index: Int,
        treeUri: Uri
    ): NotesTreeBuilder.Node? {
        if (index >= parentParts.size) return node
        val want = parentParts[index]
        val childIdx = node.children.indexOfFirst { it.name.equals(want, ignoreCase = true) }
        if (childIdx >= 0) {
            val updated = insertAncestorChain(node.children[childIdx], parentParts, index + 1, treeUri)
                ?: return null
            val next = node.children.toMutableList()
            next[childIdx] = updated
            return node.copy(children = next)
        }
        val stubRel = parentParts.take(index + 1).joinToString("/")
        val stubId = NotesTreeBuilder.folderDocumentId(treeUri, stubRel)
        val stubUri = SafTreeLister.documentUri(treeUri, stubId)
        val stub = NotesTreeBuilder.Node(
            documentId = stubUri.toString(),
            safDocumentId = stubId,
            name = want,
            relativePath = stubRel,
            folderUri = stubUri,
            indexUri = null,
            children = emptyList()
        )
        val filled = insertAncestorChain(stub, parentParts, index + 1, treeUri) ?: return null
        val nextChildren = (node.children + filled).sortedBy { it.name.lowercase() }
        return node.copy(children = nextChildren)
    }
    fun isRelativePathVisible(
        rows: List<NotesTreeBuilder.VisibleRow>,
        relativePath: String
    ): Boolean {
        val want = normalizeRelative(relativePath)
        return rows.any { normalizeRelative(it.relativePath).equals(want, ignoreCase = true) }
    }

    fun isRelativePathInTree(
        relativePaths: Iterable<String>,
        relativePath: String
    ): Boolean {
        val want = normalizeRelative(relativePath)
        return relativePaths.any { normalizeRelative(it).equals(want, ignoreCase = true) }
    }

    /**
     * Expand root + every ancestor (and the leaf) so [NotesTreeBuilder.flattenVisible] emits the path.
     */
    fun expandedIdsForPath(
        root: NotesTreeBuilder.Node,
        relativePath: String,
        existing: Set<String>
    ): Set<String> {
        val ids = existing.toMutableSet()
        ids.add(root.documentId)
        val parts = normalizeRelative(relativePath).split('/').filter { it.isNotEmpty() }
        var cur = root
        for (part in parts) {
            val child = cur.children.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: break
            ids.add(child.documentId)
            cur = child
        }
        return ids
    }

    /**
     * Insert [leaf] under its parent path. No-op if a same-name sibling already exists.
     * Parent must exist (create only adds one new folder).
     */
    fun insertCreatedLeaf(root: NotesTreeBuilder.Node, leaf: CreatedLeaf): NotesTreeBuilder.Node {
        val rel = normalizeRelative(leaf.relativePath)
        if (rel.isEmpty()) return root
        val parts = rel.split('/').filter { it.isNotEmpty() }
        val parentParts = parts.dropLast(1)
        return insertUnder(root, parentParts, 0, leaf)
            ?: root
    }

    private fun insertUnder(
        node: NotesTreeBuilder.Node,
        parentParts: List<String>,
        index: Int,
        leaf: CreatedLeaf
    ): NotesTreeBuilder.Node? {
        if (index >= parentParts.size) {
            if (node.children.any { it.name.equals(leaf.name, ignoreCase = true) }) {
                return node
            }
            val child = NotesTreeBuilder.Node(
                documentId = leaf.documentId,
                safDocumentId = leaf.safDocumentId,
                name = leaf.name,
                relativePath = normalizeRelative(leaf.relativePath),
                folderUri = android.net.Uri.parse(leaf.folderUri),
                indexUri = leaf.indexUri?.let { android.net.Uri.parse(it) },
                children = emptyList()
            )
            val nextChildren = (node.children + child).sortedBy { it.name.lowercase() }
            return node.copy(children = nextChildren)
        }
        val want = parentParts[index]
        val childIdx = node.children.indexOfFirst { it.name.equals(want, ignoreCase = true) }
        if (childIdx < 0) return null
        val updatedChild = insertUnder(node.children[childIdx], parentParts, index + 1, leaf) ?: return null
        val nextChildren = node.children.toMutableList()
        nextChildren[childIdx] = updatedChild
        return node.copy(children = nextChildren)
    }

    fun ensureCreatedVisible(
        root: NotesTreeBuilder.Node,
        leaf: CreatedLeaf,
        expandedIds: Set<String>
    ): Pair<NotesTreeBuilder.Node, Set<String>> {
        val merged = insertCreatedLeaf(root, leaf)
        val expanded = expandedIdsForPath(merged, leaf.relativePath, expandedIds)
        return merged to expanded
    }

    /**
     * After a SAF re-walk: keep every pending create visible until the scan actually lists it.
     * Overlapping/stale refreshes otherwise wipe the optimistic row (tester AT-NC-1 FAIL class).
     *
     * @return merged root, expanded ids, and pending leaves still missing from SAF (caller keeps those).
     */
    fun applyPendingCreates(
        root: NotesTreeBuilder.Node,
        pending: Collection<CreatedLeaf>,
        expandedIds: Set<String>,
        isInTree: (NotesTreeBuilder.Node, String) -> Boolean
    ): Triple<NotesTreeBuilder.Node, Set<String>, List<CreatedLeaf>> {
        var merged = root
        var expanded = expandedIds
        val stillPending = ArrayList<CreatedLeaf>(pending.size)
        for (leaf in pending) {
            val rel = normalizeRelative(leaf.relativePath)
            if (rel.isEmpty()) continue
            if (isInTree(merged, rel)) {
                // Confirmed on disk/SAF — expand ancestors so flatten shows it; drop from pending.
                expanded = expandedIdsForPath(merged, rel, expanded)
            } else {
                val (nextRoot, nextExpanded) = ensureCreatedVisible(merged, leaf, expanded)
                merged = nextRoot
                expanded = nextExpanded
                stillPending.add(leaf)
            }
        }
        return Triple(merged, expanded, stillPending)
    }
}
