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
}
