package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes

class CloudFolderTreeTest {
    @Test
    fun pickTreeRootId_prefersPhotosLibraryWhenOnTrail() {
        val hub = CloudVirtualNodes.HUB_ID
        val root = "11111111-1111-4111-8111-111111111111"
        val lib = "22222222-2222-4222-8222-222222222222"
        val day = "33333333-3333-4333-8333-333333333333"
        val crumbs = listOf(
            hub to null,
            root to CloudCodes.CLOUD_ROOT,
            lib to CloudCodes.CLOUD_FOLDER,
            day to CloudCodes.CLOUD_FOLDER
        )
        assertThat(
            CloudFolderTree.pickTreeRootId(crumbs, lib, CloudVirtualNodes::isVirtual)
        ).isEqualTo(lib)
    }

    @Test
    fun pickTreeRootId_prefersCloudRootWithoutPhotos() {
        val hub = CloudVirtualNodes.HUB_ID
        val root = "11111111-1111-4111-8111-111111111111"
        val folder = "22222222-2222-4222-8222-222222222222"
        val crumbs = listOf(
            hub to null,
            root to CloudCodes.CLOUD_ROOT,
            folder to CloudCodes.CLOUD_FOLDER
        )
        assertThat(
            CloudFolderTree.pickTreeRootId(crumbs, photosLibraryId = null, CloudVirtualNodes::isVirtual)
        ).isEqualTo(root)
    }

    @Test
    fun flatten_depthAndSelection() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val a = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val b = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val map = mapOf(
            root to listOf(CloudFolderTree.Node(a, "A"), CloudFolderTree.Node(b, "B")),
            a to emptyList(),
            b to emptyList()
        )
        val rows = CloudFolderTree.flatten(
            CloudFolderTree.Node(root, "Root"),
            map,
            selectedId = b
        )
        assertThat(rows).hasSize(3)
        assertThat(rows[0].depth).isEqualTo(0)
        assertThat(rows[2].selected).isTrue()
        assertThat(rows[2].id).isEqualTo(b)
    }

    @Test
    fun selectedFolderId_fileUsesParent() {
        val file = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val parent = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        assertThat(CloudFolderTree.selectedFolderId(true, file, parent)).isEqualTo(parent)
        assertThat(CloudFolderTree.selectedFolderId(false, file, parent)).isEqualTo(file)
    }

    @Test
    fun projectVisible_startsCollapsed_hidesGrandchildren() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val grand = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val map = mapOf(
            root to listOf(CloudFolderTree.Node(child, "Child")),
            child to listOf(CloudFolderTree.Node(grand, "Grand"))
        )
        val rows = CloudFolderTree.projectVisible(
            root = CloudFolderTree.Node(root, "Root"),
            childrenByParent = map,
            expandedIds = setOf(root),
            loadedParentIds = setOf(root, child),
            selectedId = null
        )
        assertThat(rows.map { it.id }).containsExactly(root, child).inOrder()
        assertThat(rows[1].canExpand).isTrue()
        assertThat(rows[1].isExpanded).isFalse()
    }

    @Test
    fun projectVisible_expandRevealsChildren() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val grand = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val map = mapOf(
            root to listOf(CloudFolderTree.Node(child, "Child")),
            child to listOf(CloudFolderTree.Node(grand, "Grand"))
        )
        val rows = CloudFolderTree.projectVisible(
            root = CloudFolderTree.Node(root, "Root"),
            childrenByParent = map,
            expandedIds = setOf(root, child),
            loadedParentIds = setOf(root, child),
            selectedId = grand
        )
        assertThat(rows.map { it.id }).containsExactly(root, child, grand).inOrder()
        assertThat(rows[2].selected).isTrue()
    }

    @Test
    fun projectVisible_unloadedParentStaysExpandable() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val map = mapOf(root to listOf(CloudFolderTree.Node(child, "Child")))
        val rows = CloudFolderTree.projectVisible(
            root = CloudFolderTree.Node(root, "Root"),
            childrenByParent = map,
            expandedIds = setOf(root),
            loadedParentIds = setOf(root),
            selectedId = null
        )
        assertThat(rows).hasSize(2)
        assertThat(rows[1].canExpand).isTrue()
        assertThat(rows[1].isExpanded).isFalse()
    }

    @Test
    fun projectVisible_loadedEmptyIsLeaf() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val map = mapOf(
            root to listOf(CloudFolderTree.Node(child, "Child")),
            child to emptyList()
        )
        val rows = CloudFolderTree.projectVisible(
            root = CloudFolderTree.Node(root, "Root"),
            childrenByParent = map,
            expandedIds = setOf(root),
            loadedParentIds = setOf(root, child),
            selectedId = null
        )
        assertThat(rows[1].canExpand).isFalse()
    }

    @Test
    fun collapseBranch_removesDescendants() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val grand = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val map = mapOf(
            root to listOf(CloudFolderTree.Node(child, "Child")),
            child to listOf(CloudFolderTree.Node(grand, "Grand"))
        )
        val expanded = mutableSetOf(root, child, grand)
        CloudFolderTree.collapseBranch(child, map, expanded)
        assertThat(expanded).containsExactly(root)
    }

    @Test
    fun buildAncestorTrail_walksDepthBackToRoot() {
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val a = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val b = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val rows = listOf(
            Triple(root, "Root", 0),
            Triple(a, "A", 1),
            Triple(b, "B", 2)
        )
        val trail = CloudFolderTree.buildAncestorTrail(rows, b)
        assertThat(trail.map { it.first }).containsExactly(root, a, b).inOrder()
        assertThat(trail.map { it.second }).containsExactly("Root", "A", "B").inOrder()
    }

    @Test
    fun buildAncestorTrail_unknownId_empty() {
        val rows = listOf(Triple("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Root", 0))
        assertThat(
            CloudFolderTree.buildAncestorTrail(rows, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        ).isEmpty()
    }

    @Test
    fun composeBreadcrumb_prefixesAboveTreeRoot() {
        val above = "zzzzzzzz-zzzz-4zzz-8zzz-zzzzzzzzzzzz" to "Above"
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val existing = listOf(above, root to "My Cloud", child to "Child")
        val tree = listOf(root to "My Cloud", child to "Child")
        val composed = CloudFolderTree.composeBreadcrumb(existing, tree)
        assertThat(composed).containsExactly(above, root to "My Cloud", child to "Child").inOrder()
    }

    @Test
    fun composeBreadcrumb_e9f_omitsHubPrefix() {
        val hub = CloudVirtualNodes.HUB_ID to "Cloud"
        val root = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val existing = listOf(hub, root to "My Cloud", child to "Child")
        val tree = listOf(root to "My Cloud", child to "Child")
        val composed = CloudFolderTree.composeBreadcrumb(
            existing,
            tree,
            omitPrefixId = CloudVirtualNodes::isVirtual
        )
        assertThat(composed).containsExactly(root to "My Cloud", child to "Child").inOrder()
        assertThat(composed.none { CloudBrowseRoot.isHubLanding(it.first) }).isTrue()
    }

    @Test
    fun pickTreeRootId_e9f_myCloudFirstCrumb_noHub() {
        val root = "11111111-1111-4111-8111-111111111111"
        val folder = "22222222-2222-4222-8222-222222222222"
        val crumbs = listOf(
            root to CloudCodes.CLOUD_ROOT,
            folder to CloudCodes.CLOUD_FOLDER
        )
        assertThat(
            CloudFolderTree.pickTreeRootId(crumbs, photosLibraryId = null, CloudVirtualNodes::isVirtual)
        ).isEqualTo(root)
    }

    @Test
    fun omitBrowseParentWrapper_onlyWhenTreeRootIsMyCloud() {
        val myCloud = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertThat(CloudFolderTree.omitBrowseParentWrapper(myCloud, myCloud)).isTrue()
        assertThat(CloudFolderTree.omitBrowseParentWrapper(other, myCloud)).isFalse()
        assertThat(CloudFolderTree.omitBrowseParentWrapper(myCloud, null)).isFalse()
        assertThat(CloudFolderTree.omitBrowseParentWrapper(null, myCloud)).isFalse()
        assertThat(CloudFolderTree.omitBrowseParentWrapper("", myCloud)).isFalse()
    }

    @Test
    fun projectVisibleStackedChildren_omitsWrapper_showsDepth0Roots() {
        // AT-CE21 / AT-CE22: no My Cloud row; ≥2 children as sequential depth-0 trees.
        val myCloud = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val a = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val b = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val a1 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val map = mapOf(
            myCloud to listOf(CloudFolderTree.Node(a, "Notes"), CloudFolderTree.Node(b, "Photos")),
            a to listOf(CloudFolderTree.Node(a1, "Nested")),
            b to emptyList()
        )
        val rows = CloudFolderTree.projectVisibleStackedChildren(
            browseParentId = myCloud,
            childrenByParent = map,
            expandedIds = setOf(myCloud),
            loadedParentIds = setOf(myCloud, b),
            selectedId = null
        )
        assertThat(rows).hasSize(2)
        assertThat(rows.none { it.id.equals(myCloud, ignoreCase = true) }).isTrue()
        assertThat(rows[0].id).isEqualTo(a)
        assertThat(rows[0].depth).isEqualTo(0)
        assertThat(rows[1].id).isEqualTo(b)
        assertThat(rows[1].depth).isEqualTo(0)
        assertThat(rows[0].canExpand).isTrue()
        assertThat(rows[1].canExpand).isFalse()
    }

    @Test
    fun projectVisibleStackedChildren_emptyOrUnloaded_noWrapperStub() {
        // AT-CE21 soft: Soft Ready / soft-fail must not paint lone My Cloud chrome.
        val myCloud = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertThat(
            CloudFolderTree.projectVisibleStackedChildren(
                browseParentId = myCloud,
                childrenByParent = emptyMap(),
                expandedIds = setOf(myCloud),
                loadedParentIds = emptySet(),
                selectedId = myCloud
            )
        ).isEmpty()

        val emptyKids = mapOf(myCloud to emptyList<CloudFolderTree.Node>())
        assertThat(
            CloudFolderTree.projectVisibleStackedChildren(
                browseParentId = myCloud,
                childrenByParent = emptyKids,
                expandedIds = setOf(myCloud),
                loadedParentIds = setOf(myCloud),
                selectedId = myCloud
            )
        ).isEmpty()
    }

    @Test
    fun projectVisibleStackedChildren_expandStackedRoot_revealsGrandchildren() {
        // Soft AT-CE9/CE10 per stacked root.
        val myCloud = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val a = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val a1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val map = mapOf(
            myCloud to listOf(CloudFolderTree.Node(a, "A")),
            a to listOf(CloudFolderTree.Node(a1, "A1")),
            a1 to emptyList()
        )
        val rows = CloudFolderTree.projectVisibleStackedChildren(
            browseParentId = myCloud,
            childrenByParent = map,
            expandedIds = setOf(a),
            loadedParentIds = setOf(myCloud, a, a1),
            selectedId = a1
        )
        assertThat(rows).hasSize(2)
        assertThat(rows.none { it.id.equals(myCloud, ignoreCase = true) }).isTrue()
        assertThat(rows[0].id).isEqualTo(a)
        assertThat(rows[0].depth).isEqualTo(0)
        assertThat(rows[0].isExpanded).isTrue()
        assertThat(rows[1].id).isEqualTo(a1)
        assertThat(rows[1].depth).isEqualTo(1)
        assertThat(rows[1].selected).isTrue()
    }
}
