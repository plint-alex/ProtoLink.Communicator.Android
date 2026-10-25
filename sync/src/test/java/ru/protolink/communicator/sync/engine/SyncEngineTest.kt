package ru.protolink.communicator.sync.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.protolink.communicator.sync.fakes.InMemoryFileSystem
import ru.protolink.communicator.sync.fakes.InMemoryMetadataStore
import ru.protolink.communicator.sync.fakes.InMemoryRemoteCloud
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.LocalChange
import ru.protolink.communicator.sync.model.SyncItemMeta
import ru.protolink.communicator.sync.model.SyncMapping
import java.time.Instant

class LocalChangeClassifierTest {
    private val classifier = LocalChangeClassifier()

    @Test
    fun localRename_uniqueSize_file() {
        val store = listOf(
            SyncItemMeta("m", "id1", "root", "old.txt", false, 5)
        )
        val fs = listOf(FsEntry("new.txt", false, 5))
        val changes = classifier.classify(fs, store)
        assertThat(changes.filterIsInstance<LocalChange.Renamed>()).hasSize(1)
        assertThat(changes.filterIsInstance<LocalChange.Added>()).isEmpty()
        assertThat(changes.filterIsInstance<LocalChange.Removed>()).isEmpty()
    }

    @Test
    fun localRename_uniqueSize_folder() {
        val store = listOf(
            SyncItemMeta("m", "fid", "root", "olda", true, 10)
        )
        val fs = listOf(FsEntry("newa", true, 10))
        val changes = classifier.classify(fs, store)
        assertThat(changes.filterIsInstance<LocalChange.Renamed>()).hasSize(1)
    }

    @Test
    fun localRename_ambiguousSize_fallsThroughToAddRemove() {
        val store = listOf(
            SyncItemMeta("m", "id1", "root", "a.txt", false, 5),
            SyncItemMeta("m", "id2", "root", "b.txt", false, 5)
        )
        val fs = listOf(
            FsEntry("c.txt", false, 5),
            FsEntry("d.txt", false, 5)
        )
        val changes = classifier.classify(fs, store)
        assertThat(changes.filterIsInstance<LocalChange.Renamed>()).isEmpty()
        assertThat(changes.filterIsInstance<LocalChange.Added>()).hasSize(2)
        assertThat(changes.filterIsInstance<LocalChange.Removed>()).hasSize(2)
    }

    @Test
    fun localAddRemoveUpdate() {
        val store = listOf(
            SyncItemMeta("m", "keep", "root", "keep.txt", false, 3),
            SyncItemMeta("m", "gone", "root", "gone.txt", false, 1),
            SyncItemMeta("m", "chg", "root", "chg.txt", false, 2)
        )
        val fs = listOf(
            FsEntry("keep.txt", false, 3),
            FsEntry("new.txt", false, 4),
            FsEntry("chg.txt", false, 9)
        )
        val changes = classifier.classify(fs, store)
        assertThat(changes.filterIsInstance<LocalChange.Added>()).hasSize(1)
        assertThat(changes.filterIsInstance<LocalChange.Removed>()).hasSize(1)
        assertThat(changes.filterIsInstance<LocalChange.Updated>()).hasSize(1)
    }

    @Test
    fun emptyFolders_sizeZero_notUniqueRename() {
        val store = listOf(
            SyncItemMeta("m", "f1", "root", "a", true, 0),
            SyncItemMeta("m", "f2", "root", "b", true, 0)
        )
        val fs = listOf(
            FsEntry("c", true, 0),
            FsEntry("d", true, 0)
        )
        val changes = classifier.classify(fs, store)
        assertThat(changes.filterIsInstance<LocalChange.Renamed>()).isEmpty()
    }
}

class SyncEngineTest {
    private val root = "/local/root"
    private val mapping = SyncMapping("m1", "cloud-root", root, "Root")

    private fun harness(): Triple<InMemoryMetadataStore, InMemoryFileSystem, InMemoryRemoteCloud> {
        val store = InMemoryMetadataStore()
        val fs = InMemoryFileSystem()
        val remote = InMemoryRemoteCloud()
        remote.seedFolder("cloud-root", "user", "Root")
        return Triple(store, fs, remote)
    }

    @Test
    fun localAdd_uploadsAndStores() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "hello.txt", "hi".toByteArray())
        // seed empty remote; bootstrap does nothing useful; local add uploads
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(store.getAll("m1").any { it.relativePath == "hello.txt" }).isTrue()
        assertThat(remote.childrenOf("cloud-root").any { it.name.equals("hello.txt", true) }).isTrue()
    }

    @Test
    fun localRemove_deletesRemote() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "x.txt", "abc".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        fs.delete(root, "x.txt", false)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(store.getAll("m1")).isEmpty()
        assertThat(remote.childrenOf("cloud-root")).isEmpty()
    }

    @Test
    fun localUpdate_uploadsNewSize() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "x.txt", "ab".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "x.txt", "abcdef".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(remote.get(id)!!.bytes!!.size).isEqualTo(6)
        assertThat(store.getAll("m1").first().sizeBytes).isEqualTo(6)
    }

    @Test
    fun sameSizeContentChange_uploads_by_hash() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "aaaa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "films/index.html" }.remoteId
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("aaaa")

        // Same byte length, different content — must still upload (Films-style edits).
        fs.writeFile(root, "films/index.html", "bbbb".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("bbbb")
        assertThat(store.getAll("m1").first { it.relativePath == "films/index.html" }.sizeBytes)
            .isEqualTo(4)
    }

    @Test
    fun emptyContentHash_seedsLocalHash_thenSameSizeEditUploads() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "aaaa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        val id = meta.remoteId
        // Upgrade simulation: clear hash. Do not download every remote (that stalled live sync).
        store.upsert(meta.copy(contentHash = "", sizeBytes = 4, remoteUpdateTime = Instant.EPOCH))

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        val seeded = store.getAll("m1").first { it.relativePath == "films/index.html" }
        assertThat(seeded.contentHash).isNotEmpty()
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("aaaa")

        fs.writeFile(root, "films/index.html", "bbbb".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("bbbb")
    }

    @Test
    fun emptyContentHash_seeds_whenCompareDisabled() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "aaaa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        store.upsert(meta.copy(contentHash = "", sizeBytes = 4))

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(store.getAll("m1").first { it.relativePath == "films/index.html" }.contentHash)
            .isNotEmpty()
    }

    @Test
    fun comparePhase_nullDownload_softFailsAndContinuesSibling() = runTest {
        val (store, fs, inner) = harness()
        fs.writeFile(root, "keep.html", "keep-local-v2-body".toByteArray())
        fs.writeFile(root, "fail.html", "fail-local-body-xx".toByteArray())
        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        val keepId = store.getAll("m1").first { it.relativePath == "keep.html" }.remoteId
        val failId = store.getAll("m1").first { it.relativePath == "fail.html" }.remoteId
        val keepRemoteOld = "keep-remote-old"
        inner.uploadFile(keepId, "keep.html", keepRemoteOld.toByteArray(), "text/html")
        store.upsert(
            store.getAll("m1").first { it.relativePath == "keep.html" }.copy(
                contentHash = ContentHashUtil.sha256Hex(keepRemoteOld.toByteArray()),
                remoteUpdateTime = Instant.EPOCH
            )
        )
        store.upsert(
            store.getAll("m1").first { it.relativePath == "fail.html" }.copy(
                contentHash = "bb",
                remoteUpdateTime = Instant.EPOCH
            )
        )
        fs.writeFile(root, "keep.html", "keep-local-v2-body".toByteArray())
        fs.writeFile(root, "fail.html", "fail-local-body-xx".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? =
                if (entityId == failId) null else inner.downloadFile(entityId)
        }
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(inner.get(keepId)!!.bytes!!)).isEqualTo("keep-local-v2-body")
        assertThat(String(inner.get(failId)!!.bytes!!)).isEqualTo("fail-local-body-xx")
        assertThat(store.getAll("m1").first { it.relativePath == "fail.html" }.contentHash)
            .isEqualTo("bb")
        assertThat(store.getAll("m1").first { it.relativePath == "keep.html" }.contentHash)
            .isEqualTo(ContentHashUtil.sha256Hex("keep-local-v2-body".toByteArray()))
    }

    @Test
    fun comparePhase_nullDownload_doesNotOverwriteCloud() = runTest {
        val (store, fs, inner) = harness()
        fs.writeFile(root, "note.html", "local".toByteArray())
        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "note.html" }.remoteId
        fs.writeFile(root, "note.html", "NEWER".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? = null
            override suspend fun fileSize(entityId: String): Long? = null
        }
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        // Soft-fail: pass completes; without remote bytes we must not push local over unknown cloud.
        assertThat(String(inner.get(id)!!.bytes!!)).isEqualTo("local")
        assertThat(String(fs.readFile(root, "note.html"))).isEqualTo("NEWER")
    }

    @Test
    fun adoptLocalAdd_nullDownload_softFailsAndContinuesSibling() = runTest {
        val (store, fs, inner) = harness()
        val keepRemoteOld = "keep-remote-old"
        val keepBody = "keep-sibling-upload-body"
        // Long enough that localLooksStub is false.
        val adoptBody = "adopt-local-add-body-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        val keepFolder = inner.addFolder("cloud-root", "keep")
        val adoptFolder = inner.addFolder("cloud-root", "adopt")
        val keepRemote = inner.addFile(keepFolder, "index.html", keepRemoteOld.toByteArray(), "text/html")
        val adoptRemote = inner.addFile(adoptFolder, "index.html", "adopt-cloud".toByteArray(), "text/html")
        // keep has meta (compare → Write); adopt has no meta → local Added adopt path.
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = keepRemote,
                parentRemoteId = keepFolder,
                relativePath = "keep/index.html",
                isFolder = false,
                sizeBytes = 1,
                contentHash = ContentHashUtil.sha256Hex(keepRemoteOld.toByteArray()),
                remoteUpdateTime = Instant.EPOCH
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = keepFolder,
                parentRemoteId = "cloud-root",
                relativePath = "keep",
                isFolder = true,
                sizeBytes = 0
            )
        )
        fs.writeFile(root, "keep/index.html", keepBody.toByteArray())
        fs.writeFile(root, "adopt/index.html", adoptBody.toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? =
                if (entityId == adoptRemote) null else inner.downloadFile(entityId)
        }
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(inner.get(keepRemote)!!.bytes!!)).isEqualTo(keepBody)
        assertThat(String(inner.get(adoptRemote)!!.bytes!!)).isEqualTo("adopt-cloud")
        assertThat(store.getAll("m1").none { it.remoteId == adoptRemote }).isTrue()
    }

    @Test
    fun comparePhase_emptyRemoteBytes_reachesHashDecideNotDownloadFailure() = runTest {
        val (store, fs, inner) = harness()
        // Non-stub local (len >= 64) so compare reaches hash decide with empty remote bytes.
        val localBody = "x".repeat(64)
        fs.writeFile(root, "note/index.html", localBody.toByteArray())
        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "note/index.html" }.remoteId
        inner.uploadFile(id, "index.html", ByteArray(0), "text/html")
        store.upsert(
            store.getAll("m1").first { it.relativePath == "note/index.html" }.copy(
                contentHash = "aa",
                remoteUpdateTime = Instant.EPOCH
            )
        )
        fs.writeFile(root, "note/index.html", localBody.toByteArray())

        var threw: Throwable? = null
        try {
            SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        } catch (t: Throwable) {
            threw = t
        }
        assertThat(threw).isInstanceOf(SyncConflictException::class.java)
        assertThat(threw!!.message).doesNotContain("returned empty")
        assertThat(threw.message!!.lowercase()).doesNotContain("download failed")
    }

    @Test
    fun comparePhase_emptyRemoteMatchingEmptyLocal_completesWithoutThrow() = runTest {
        val (store, fs, inner) = harness()
        // Seed non-empty first so bootstrap/meta exists, then both sides empty.
        fs.writeFile(root, "note/index.html", "seed".toByteArray())
        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "note/index.html" }.remoteId
        val emptyHash = ContentHashUtil.sha256Hex(ByteArray(0))
        inner.uploadFile(id, "index.html", ByteArray(0), "text/html")
        fs.writeFile(root, "note/index.html", ByteArray(0))
        store.upsert(
            store.getAll("m1").first { it.relativePath == "note/index.html" }.copy(
                sizeBytes = 0,
                contentHash = emptyHash,
                remoteUpdateTime = Instant.now()
            )
        )

        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))

        assertThat(fs.readFile(root, "note/index.html")).isEmpty()
        assertThat(inner.get(id)!!.bytes!!).isEmpty()
    }

    @Test
    fun localMatchesMeta_remoteChanged_downloads() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "Test4".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        val id = meta.remoteId
        // Other device uploaded Test5; local still Test4 matching meta.
        remote.uploadFile(id, "index.html", "Test5".toByteArray(), "text/html")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("Test5")
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("Test5")
    }

    @Test
    fun localRename_keepsRemoteId() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "old.txt", "unique-content!!".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.move(root, "old.txt", "new.txt", false)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(store.getAll("m1").single().remoteId).isEqualTo(id)
        assertThat(store.getAll("m1").single().relativePath).isEqualTo("new.txt")
        assertThat(remote.get(id)!!.name).isEqualTo("new.txt")
    }

    @Test
    fun remoteAdd_downloads() = runTest {
        val (store, fs, remote) = harness()
        // First reconcile seeds empty
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        remote.seedFile("r1", "cloud-root", "from-server.txt", "srv".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(fs.exists(root, "from-server.txt")).isTrue()
        assertThat(String(fs.readFile(root, "from-server.txt"))).isEqualTo("srv")
    }

    @Test
    fun remoteRemove_deletesLocal() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "z.txt", "z".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        remote.delete(id)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(fs.exists(root, "z.txt")).isFalse()
        assertThat(store.getAll("m1")).isEmpty()
    }

    @Test
    fun bothChanged_diverged_throwsConflict() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "c.txt", "aa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "c.txt", "local-wins-content".toByteArray())
        remote.uploadFile(id, "c.txt", "REMOTE".toByteArray(), "text/plain")
        try {
            SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
            throw AssertionError("expected SyncConflictException")
        } catch (e: SyncConflictException) {
            assertThat(e.relativePath).isEqualTo("c.txt")
        }
        // Unchanged until Force Upload / Force Download
        assertThat(String(fs.readFile(root, "c.txt"))).isEqualTo("local-wins-content")
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("REMOTE")
    }

    @Test
    fun forcePush_overwritesRemote() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "c.txt", "aa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "c.txt", "FORCE-LOCAL".toByteArray())
        remote.uploadFile(id, "c.txt", "REMOTE".toByteArray(), "text/plain")
        SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("FORCE-LOCAL")
        assertThat(store.getAll("m1").first { it.relativePath == "c.txt" }.contentHash)
            .isEqualTo(ContentHashUtil.sha256Hex("FORCE-LOCAL".toByteArray()))
    }

    @Test
    fun forcePull_overwritesLocal() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "c.txt", "aa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "c.txt", "LOCAL".toByteArray())
        remote.uploadFile(id, "c.txt", "FORCE-REMOTE".toByteArray(), "text/plain")
        SyncEngine(store, fs, remote).forcePullMapping(mapping)
        assertThat(String(fs.readFile(root, "c.txt"))).isEqualTo("FORCE-REMOTE")
        assertThat(store.getAll("m1").first { it.relativePath == "c.txt" }.contentHash)
            .isEqualTo(ContentHashUtil.sha256Hex("FORCE-REMOTE".toByteArray()))
    }

    @Test
    fun localEdited_remoteUnchanged_writes() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "c.txt", "aa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "c.txt", "local-only-edit".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "c.txt"))).isEqualTo("local-only-edit")
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("local-only-edit")
    }

    @Test
    fun remoteRename_acrossTwoMappings_movesOwnership() = runTest {
        val store = InMemoryMetadataStore()
        val fs = InMemoryFileSystem()
        val remote = InMemoryRemoteCloud()
        remote.seedFolder("root-a", "user", "A")
        remote.seedFolder("root-b", "user", "B")
        val mapA = SyncMapping("ma", "root-a", "/a", "A")
        val mapB = SyncMapping("mb", "root-b", "/b", "B")
        fs.writeFile("/a", "shared.txt", "data-xyz".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapA, mapB))
        val id = store.getAll("ma").first { it.relativePath == "shared.txt" }.remoteId
        remote.move(id, "root-a", "root-b")
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapA, mapB))
        assertThat(store.findByRemoteId(id)?.mappingId).isEqualTo("mb")
        assertThat(fs.exists("/a", "shared.txt")).isFalse()
        assertThat(fs.exists("/b", "shared.txt")).isTrue()
    }

    @Test
    fun bootstrap_emptyStore_doesNotDeleteRemote() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFile("keep", "cloud-root", "keep.txt", "k".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(remote.get("keep")).isNotNull()
        assertThat(fs.exists(root, "keep.txt")).isTrue()
        assertThat(store.getAll("m1").any { it.remoteId == "keep" }).isTrue()
    }

    @Test
    fun emptyLocalStub_readsRemote_evenWhenListingOmitsSize() = runTest {
        val (store, fs, inner) = harness()
        // Production API listings omit sizeBytes; fileSize() still returns Content-Length.
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(parentId: String) =
                inner.listChildrenPaged(parentId).map { it.copy(sizeBytes = null) }
        }
        inner.seedFolder("films-folder", "cloud-root", "Films")
        inner.seedFile("films-idx", "films-folder", "index.html", "<p>Movie notes</p>".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie notes</p>")

        // Corrupt to empty stub + meta size 0 (the Films bug state)
        fs.writeFile(root, "films/index.html", ByteArray(0))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        store.upsert(meta.copy(sizeBytes = 0))

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie notes</p>")
        assertThat(store.getAll("m1").first { it.relativePath == "films/index.html" }.sizeBytes)
            .isEqualTo("<p>Movie notes</p>".length.toLong())
    }

    @Test
    fun emptyLocalStub_readsRemote_whenRemoteSizeCompletelyUnknown() = runTest {
        val (store, fs, inner) = harness()
        // Listing and fileSize() both omit size — decide must still Read empty stubs.
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(parentId: String) =
                inner.listChildrenPaged(parentId).map { it.copy(sizeBytes = null) }
            override suspend fun fileSize(entityId: String): Long? = null
        }
        inner.seedFolder("films-folder", "cloud-root", "Films")
        inner.seedFile("films-idx", "films-folder", "index.html", "<p>Movie notes</p>".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie notes</p>")

        fs.writeFile(root, "films/index.html", ByteArray(0))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        store.upsert(meta.copy(sizeBytes = 0))

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie notes</p>")
    }

    @Test
    fun listChildren_paging_viaFakeReturnsAll() = runTest {
        val (store, fs, remote) = harness()
        repeat(5) { i ->
            remote.seedFile("f$i", "cloud-root", "f$i.txt", "x$i".toByteArray())
        }
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(store.getAll("m1").filter { !it.isFolder }).hasSize(5)
    }
}
