package ru.protolink.communicator.util

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.protolink.communicator.data.NotesTreeBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotesTreeCreateVisibilityTest {

    private fun node(
        name: String,
        relativePath: String,
        children: List<NotesTreeBuilder.Node> = emptyList(),
        id: String = "id:$relativePath"
    ): NotesTreeBuilder.Node {
        val uri = Uri.parse("content://tree/$id")
        return NotesTreeBuilder.Node(
            documentId = uri.toString(),
            safDocumentId = id,
            name = name,
            relativePath = relativePath,
            folderUri = uri,
            indexUri = null,
            children = children
        )
    }

    @Test
    fun ensureCreatedVisible_insertsRootChild_andExpandsRoot() {
        val root = node("Notes", "", listOf(node("Existing", "Existing")))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "NewPage",
            relativePath = "NewPage",
            documentId = "content://tree/id:NewPage",
            safDocumentId = "id:NewPage",
            folderUri = "content://tree/id:NewPage",
            indexUri = "content://tree/id:NewPage/index.html"
        )
        val (merged, expanded) = NotesTreeCreateVisibility.ensureCreatedVisible(
            root, leaf, expandedIds = emptySet()
        )
        assertThat(merged.children.map { it.name }).containsExactly("Existing", "NewPage").inOrder()
        assertThat(expanded).contains(root.documentId)
        val rows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(rows, "NewPage")).isTrue()
    }

    @Test
    fun ensureCreatedVisible_nestedUnderCollapsedParent_becomesVisible() {
        val parent = node("Parent", "Parent")
        val root = node("Notes", "", listOf(parent))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "Child",
            relativePath = "Parent/Child",
            documentId = "content://tree/id:Parent/Child",
            safDocumentId = "id:Parent/Child",
            folderUri = "content://tree/id:Parent/Child",
            indexUri = null
        )
        // Root expanded only — Parent collapsed (create-not-visible repro).
        val (merged, expanded) = NotesTreeCreateVisibility.ensureCreatedVisible(
            root, leaf, expandedIds = setOf(root.documentId)
        )
        assertThat(expanded).containsAtLeast(root.documentId, parent.documentId)
        val rows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(rows, "Parent/Child")).isTrue()
        assertThat(rows.any { it.name == "Child" && it.depth == 2 }).isTrue()
    }

    @Test
    fun insertCreatedLeaf_duplicateName_isNoOp() {
        val root = node("Notes", "", listOf(node("Dup", "Dup")))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "Dup",
            relativePath = "Dup",
            documentId = "content://other",
            safDocumentId = "other",
            folderUri = "content://other",
            indexUri = null
        )
        val merged = NotesTreeCreateVisibility.insertCreatedLeaf(root, leaf)
        assertThat(merged.children).hasSize(1)
        assertThat(merged.children[0].documentId).isEqualTo(root.children[0].documentId)
    }

    @Test
    fun expandOnly_withoutReflatten_hidesNewChild_untilFlatten() {
        // Documents the bug: expanding ids alone does not update visible rows.
        val parent = node("Parent", "Parent")
        val root = node("Notes", "", listOf(parent))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "Child",
            relativePath = "Parent/Child",
            documentId = "content://tree/child",
            safDocumentId = "child",
            folderUri = "content://tree/child",
            indexUri = null
        )
        val merged = NotesTreeCreateVisibility.insertCreatedLeaf(root, leaf)
        val collapsedRows = NotesTreeBuilder.flattenVisible(merged, setOf(root.documentId))
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(collapsedRows, "Parent/Child")).isFalse()
        val expanded = NotesTreeCreateVisibility.expandedIdsForPath(merged, "Parent/Child", setOf(root.documentId))
        val visibleRows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(visibleRows, "Parent/Child")).isTrue()
    }

    @Test
    fun applyPendingCreates_reinsertsLeaf_afterSafRefreshMiss() {
        // SAF refresh returns tree without the just-created page — pending merge must restore it.
        val rootAfterSaf = node("Notes", "", listOf(node("Existing", "Existing")))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "NewPage",
            relativePath = "NewPage",
            documentId = "content://tree/id:NewPage",
            safDocumentId = "id:NewPage",
            folderUri = "content://tree/id:NewPage",
            indexUri = "content://tree/id:NewPage/index.html"
        )
        val (merged, expanded, stillPending) = NotesTreeCreateVisibility.applyPendingCreates(
            root = rootAfterSaf,
            pending = listOf(leaf),
            expandedIds = setOf(rootAfterSaf.documentId),
            isInTree = { r, rel ->
                NotesTreeCreateVisibility.isRelativePathInTree(
                    collectRelativePaths(r),
                    rel
                )
            }
        )
        assertThat(stillPending).hasSize(1)
        assertThat(merged.children.map { it.name }).containsExactly("Existing", "NewPage").inOrder()
        val rows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(rows, "NewPage")).isTrue()
    }

    @Test
    fun applyPendingCreates_dropsPending_whenSafListsLeaf() {
        val leafNode = node("NewPage", "NewPage")
        val rootAfterSaf = node("Notes", "", listOf(node("Existing", "Existing"), leafNode))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "NewPage",
            relativePath = "NewPage",
            documentId = "content://other",
            safDocumentId = "other",
            folderUri = "content://other",
            indexUri = null
        )
        val (merged, expanded, stillPending) = NotesTreeCreateVisibility.applyPendingCreates(
            root = rootAfterSaf,
            pending = listOf(leaf),
            expandedIds = emptySet(),
            isInTree = { r, rel ->
                NotesTreeCreateVisibility.isRelativePathInTree(collectRelativePaths(r), rel)
            }
        )
        assertThat(stillPending).isEmpty()
        assertThat(merged.children).hasSize(2)
        assertThat(merged.children.any { it.documentId == leafNode.documentId }).isTrue()
        assertThat(expanded).contains(rootAfterSaf.documentId)
        val rows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(rows, "NewPage")).isTrue()
    }

    @Test
    fun applyPendingCreates_nestedUnderParent_survivesRefreshWipe() {
        val parent = node("Parent", "Parent")
        val rootAfterSaf = node("Notes", "", listOf(parent))
        val leaf = NotesTreeCreateVisibility.CreatedLeaf(
            name = "Child",
            relativePath = "Parent/Child",
            documentId = "content://tree/id:Parent/Child",
            safDocumentId = "id:Parent/Child",
            folderUri = "content://tree/id:Parent/Child",
            indexUri = null
        )
        val (merged, expanded, stillPending) = NotesTreeCreateVisibility.applyPendingCreates(
            root = rootAfterSaf,
            pending = listOf(leaf),
            expandedIds = setOf(rootAfterSaf.documentId),
            isInTree = { r, rel ->
                NotesTreeCreateVisibility.isRelativePathInTree(collectRelativePaths(r), rel)
            }
        )
        assertThat(stillPending).hasSize(1)
        val rows = NotesTreeBuilder.flattenVisible(merged, expanded)
        assertThat(NotesTreeCreateVisibility.isRelativePathVisible(rows, "Parent/Child")).isTrue()
    }

    private fun collectRelativePaths(root: NotesTreeBuilder.Node): List<String> {
        val out = ArrayList<String>()
        fun walk(n: NotesTreeBuilder.Node) {
            if (n.relativePath.isNotEmpty()) out.add(n.relativePath)
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }
}
