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
    fun noRemoteBytes_throwsAndDoesNotOverwriteCloud() = runTest {
        val (store, fs, inner) = harness()
        fs.writeFile(root, "note.html", "local".toByteArray())
        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "note.html" }.remoteId
        // Cloud still has "local"; device edits offline.
        fs.writeFile(root, "note.html", "NEWER".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? = null
            override suspend fun fileSize(entityId: String): Long? = null
        }
        try {
            SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
            throw AssertionError("expected SyncException")
        } catch (e: SyncException) {
            assertThat(e.relativePath).isEqualTo("note.html")
        }

        // Without remote bytes we must not push local over unknown cloud content.
        assertThat(String(inner.get(id)!!.bytes!!)).isEqualTo("local")
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
