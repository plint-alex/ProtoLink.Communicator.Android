package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.ports.LocalFileSystem
import java.util.concurrent.ConcurrentHashMap

/** E10 / AT-CFD offline: confirm copy, CanWrite gate, recursive order, mirror soft-fail. */
class CloudFolderDeleteTest {
    @Test
    fun canDelete_myCloud_true_virtual_false() {
        val my = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val crumbs = listOf(CloudVirtualNodes.HUB_ID, my)
        assertThat(
            CloudFolderDelete.canDeleteItem(false, my, crumbs, emptyMap())
        ).isTrue()
        assertThat(
            CloudFolderDelete.canDeleteItem(true, my, crumbs, emptyMap())
        ).isFalse()
        assertThat(
            CloudFolderDelete.canDeleteItem(
                false,
                CloudVirtualNodes.SHARED_WITH_ME_ID,
                crumbs,
                emptyMap()
            )
        ).isFalse()
    }

    @Test
    fun canDelete_sharedReadOnly_false_write_true() {
        val readRoot = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val writeRoot = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val canWrite = mapOf(
            readRoot.lowercase() to false,
            writeRoot.lowercase() to true
        )
        val readCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            readRoot
        )
        val writeCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            writeRoot
        )
        assertThat(
            CloudFolderDelete.canDeleteItem(false, readRoot, readCrumbs, canWrite)
        ).isFalse()
        assertThat(
            CloudFolderDelete.canDeleteItem(false, writeRoot, writeCrumbs, canWrite)
        ).isTrue()
    }

    @Test
    fun confirmBody_folderNamesContents() {
        val en = CloudFolderDelete.confirmBody("en", "Docs", isFolder = true)
        assertThat(en).contains("Docs")
        assertThat(en.lowercase()).contains("all its contents")
        val ru = CloudFolderDelete.confirmBody("ru", "Docs", isFolder = true)
        assertThat(ru).contains("Docs")
        assertThat(ru).contains("содержимое")
    }

    @Test
    fun resolveMirror_childUnderMappedBreadcrumb() {
        val mapId = "11111111-1111-4111-8111-111111111111"
        val childId = "22222222-2222-4222-8222-222222222222"
        val breadcrumb = listOf(
            CloudVirtualNodes.HUB_ID to "Cloud",
            mapId to "Mapped",
            "33333333-3333-4333-8333-333333333333" to "Nested"
        )
        val mappings = listOf(
            CloudSyncMappingDto(mapId, "content://tree/mapped", "Mapped")
        )
        val target = CloudFolderDelete.resolveMirrorTarget(
            itemId = childId,
            itemName = "photo.jpg",
            breadcrumb = breadcrumb,
            mappings = mappings
        )
        assertThat(target).isNotNull()
        assertThat(target!!.rootUri).isEqualTo("content://tree/mapped")
        assertThat(target.relativePath).isEqualTo("Nested/photo.jpg")
        assertThat(target.isMappedRoot).isFalse()
    }

    @Test
    fun resolveMirror_mappedRoot() {
        val mapId = "11111111-1111-4111-8111-111111111111"
        val mappings = listOf(
            CloudSyncMappingDto(mapId, "content://tree/mapped", "Mapped")
        )
        val target = CloudFolderDelete.resolveMirrorTarget(
            itemId = mapId,
            itemName = "Mapped",
            breadcrumb = listOf(CloudVirtualNodes.HUB_ID to "Cloud"),
            mappings = mappings
        )
        assertThat(target).isNotNull()
        assertThat(target!!.isMappedRoot).isTrue()
        assertThat(target.relativePath).isEmpty()
    }

    @Test
    fun softDelete_missingPath_returnsTrue() {
        val fs = FakeLocalFs()
        val ok = CloudFolderDelete.softDeleteLocalMirror(
            fs,
            CloudFolderDelete.MirrorTarget("content://tree/x", "gone.txt", false),
            isFolder = false
        )
        assertThat(ok).isTrue()
        assertThat(fs.deleted).isEmpty()
    }

    @Test
    fun softDelete_existingFile_removes() {
        val fs = FakeLocalFs()
        fs.files["a.txt"] = byteArrayOf(1)
        val ok = CloudFolderDelete.softDeleteLocalMirror(
            fs,
            CloudFolderDelete.MirrorTarget("root", "a.txt", false),
            isFolder = false
        )
        assertThat(ok).isTrue()
        assertThat(fs.deleted).contains("a.txt")
        assertThat(fs.files.containsKey("a.txt")).isFalse()
    }

    @Test
    fun softDelete_ioFailure_softFails() {
        val fs = FakeLocalFs(failDelete = true)
        fs.files["bad.txt"] = byteArrayOf(1)
        val ok = CloudFolderDelete.softDeleteLocalMirror(
            fs,
            CloudFolderDelete.MirrorTarget("root", "bad.txt", false),
            isFolder = false
        )
        assertThat(ok).isFalse()
    }

    @Test
    fun deleteFolderRecursive_depthFirst_filesThenFoldersThenSelf() = runBlocking {
        val order = mutableListOf<String>()
        val deleted = linkedSetOf<String>()
        val tree = mapOf(
            "root" to listOf(
                CloudFolderDelete.ChildRef("file1", CloudCodes.CLOUD_FILE),
                CloudFolderDelete.ChildRef("sub", CloudCodes.CLOUD_FOLDER),
                CloudFolderDelete.ChildRef("junk", "SomeOtherCode"),
                CloudFolderDelete.ChildRef("root", CloudCodes.CLOUD_FOLDER) // self-id skip
            ),
            "sub" to listOf(
                CloudFolderDelete.ChildRef("file2", CloudCodes.CLOUD_FILE)
            )
        )
        CloudFolderDelete.deleteFolderRecursive(
            folderId = "root",
            listChildren = { id -> tree[id].orEmpty() },
            deleteEntity = { id -> order.add(id) },
            deletedIds = deleted
        )
        assertThat(order).containsExactly("file1", "file2", "sub", "root").inOrder()
        assertThat(deleted).containsExactly("file1", "file2", "sub", "root").inOrder()
        assertThat(order).doesNotContain("junk")
    }

    @Test
    fun normalizeRemoteId_dashInsensitive() {
        assertThat(CloudFolderDelete.normalizeRemoteId("AAAAAAAA-aaaa-4aaa-8aaa-AAAAAAAAAAAA"))
            .isEqualTo("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa")
    }

    private class FakeLocalFs(
        private val failDelete: Boolean = false
    ) : LocalFileSystem {
        val files = ConcurrentHashMap<String, ByteArray>()
        val folders = ConcurrentHashMap.newKeySet<String>()
        val deleted = mutableListOf<String>()

        override fun snapshot(rootPath: String, computeContentHashes: Boolean): List<FsEntry> =
            folders.map { FsEntry(it, true, 0) } +
                files.map { FsEntry(it.key, false, it.value.size.toLong()) }

        override fun listImmediate(rootPath: String, relativePath: String): List<FsEntry> {
            val parent = relativePath.trim('/').lowercase()
            fun isImmediate(rel: String): Boolean {
                val n = rel.replace('\\', '/').trim('/')
                val p = n.substringBeforeLast('/', missingDelimiterValue = "")
                return p.equals(parent, ignoreCase = true) || (parent.isEmpty() && !n.contains('/'))
            }
            return folders.filter(::isImmediate).map { FsEntry(it, true, 0) } +
                files.keys.filter(::isImmediate).map { FsEntry(it, false, files.getValue(it).size.toLong()) }
        }

        override fun createDirectory(rootPath: String, relativePath: String) {
            folders.add(relativePath)
        }

        override fun writeFile(rootPath: String, relativePath: String, bytes: ByteArray) {
            files[relativePath] = bytes
        }

        override fun readFile(rootPath: String, relativePath: String): ByteArray =
            files[relativePath] ?: error("missing")

        override fun delete(rootPath: String, relativePath: String, isFolder: Boolean) {
            if (failDelete) error("io")
            deleted.add(relativePath)
            if (isFolder) folders.remove(relativePath) else files.remove(relativePath)
        }

        override fun move(
            rootPath: String,
            fromRelative: String,
            toRelative: String,
            isFolder: Boolean
        ) = Unit

        override fun exists(rootPath: String, relativePath: String): Boolean =
            files.containsKey(relativePath) || folders.contains(relativePath)
    }
}
