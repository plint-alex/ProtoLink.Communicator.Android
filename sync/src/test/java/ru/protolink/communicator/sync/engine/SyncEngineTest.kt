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
    fun comparePhase_emptyRemoteBytes_cloudWinsWithoutThrow() = runTest {
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

        SyncEngine(store, fs, inner).reconcileAll(listOf(mapping))

        assertThat(fs.readFile(root, "note/index.html")).isEmpty()
        assertThat(inner.get(id)!!.bytes!!).isEmpty()
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
    fun bothChanged_diverged_cloudWins() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "c.txt", "aa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first().remoteId
        fs.writeFile(root, "c.txt", "local-wins-content".toByteArray())
        remote.uploadFile(id, "c.txt", "REMOTE".toByteArray(), "text/plain")
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "c.txt"))).isEqualTo("REMOTE")
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

    /** Same-name Cloud sibling: ForcePush with empty meta must reuse existing file, not AddFile again. */
    @Test
    fun forcePush_existingSameNameFile_reusesId_noSecondEntity() = runTest {
        val (store, fs, remote) = harness()
        val existingId = remote.addFile(
            "cloud-root", "index.html", "cloud-v1".toByteArray(), "text/html"
        )
        fs.writeFile(root, "index.html", "local-v2".toByteArray())
        SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(remote.childrenOf("cloud-root").filter { !it.isFolder && it.name.equals("index.html", true) })
            .hasSize(1)
        assertThat(store.getAll("m1").first { it.relativePath == "index.html" }.remoteId)
            .isEqualTo(existingId)
        assertThat(String(remote.get(existingId)!!.bytes!!)).isEqualTo("local-v2")
    }

    /** Same-name Cloud sibling: ForcePush must reuse existing folder id. */
    @Test
    fun forcePush_existingSameNameFolder_reusesId_noSecondFolder() = runTest {
        val (store, fs, remote) = harness()
        val existingFolder = remote.addFolder("cloud-root", "Agents")
        fs.createDirectory(root, "Agents")
        fs.writeFile(root, "Agents/note.txt", "x".toByteArray())
        SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(remote.childrenOf("cloud-root").filter { it.isFolder && it.name.equals("Agents", true) })
            .hasSize(1)
        assertThat(store.getAll("m1").first { it.relativePath == "agents" }.remoteId)
            .isEqualTo(existingFolder)
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

    /**
     * FAIL class P1-20261116-reverify: null download of one remote (at-pc-summary.txt-class)
     * must soft-fail and continue so sibling folders (films/…) still fill.
     */
    @Test
    fun forcePull_nullDownload_softFailsAndContinuesSibling() = runTest {
        val (store, fs, inner) = harness()
        val filmsFolder = inner.addFolder("cloud-root", "films")
        val filmsId = inner.addFile(filmsFolder, "index.html", "<p>Movie</p>".toByteArray(), "text/html")
        val failId = inner.addFile("cloud-root", "at-pc-summary.txt", "pc".toByteArray(), "text/plain")
        fs.createDirectory(root, "films")

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? =
                if (entityId == failId) null else inner.downloadFile(entityId)
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie</p>")
        assertThat(store.getAll("m1").any { it.remoteId == filmsId }).isTrue()
        assertThat(fs.exists(root, "at-pc-summary.txt")).isFalse()
    }

    /** Force download mirrors remote: local-without-cloud dirs/files are pruned. */
    @Test
    fun forcePull_prunesLocalAbsentFromRemote() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        fs.writeFile(root, "films/index.html", "stale".toByteArray())
        fs.writeFile(root, "tor browser/readme.txt", "junk".toByteArray())
        fs.createDirectory(root, "confluence")
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "ghost-meta",
                parentRemoteId = "cloud-root",
                relativePath = "tor browser",
                isFolder = true,
                sizeBytes = 0
            )
        )

        SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(fs.exists(root, "tor browser")).isFalse()
        assertThat(fs.exists(root, "tor browser/readme.txt")).isFalse()
        assertThat(fs.exists(root, "confluence")).isFalse()
        assertThat(store.getAll("m1").none { it.relativePath.contains("tor") }).isTrue()
    }

    @Test
    fun forcePull_emptyRemoteBytes_writesAndContinues() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFile("empty-f", "cloud-root", "empty.txt", ByteArray(0))
        remote.seedFile("ok-f", "cloud-root", "ok.txt", "ok".toByteArray())

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(fs.readFile(root, "empty.txt")).isEmpty()
        assertThat(String(fs.readFile(root, "ok.txt"))).isEqualTo("ok")
    }

    /**
     * FAIL class P1-20261117: SAF SingleDocumentFile.listFiles → exists threw and aborted Force.
     * Folder exists/create must soft-fail so sibling files still fill.
     */
    @Test
    fun forcePull_existsThrowsOnFolder_softFailsAndContinuesFiles() = runTest {
        val (store, innerFs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        remote.seedFile("ok-f", "cloud-root", "ok.txt", "ok".toByteArray())

        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun exists(rootPath: String, relativePath: String): Boolean {
                if (PathUtil.normalize(relativePath) == "films") {
                    throw UnsupportedOperationException("SingleDocumentFile.listFiles")
                }
                return innerFs.exists(rootPath, relativePath)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "ok.txt"))).isEqualTo("ok")
    }

    /**
     * FAIL class P1-20261117 walkRemote duration: wide sibling folders must still Force-fill
     * after parallel walk (Win WalkRemoteParallel parity).
     */
    @Test
    fun forcePull_wideSiblingTree_fillsAllLeaves() = runTest {
        val (store, fs, remote) = harness()
        repeat(24) { i ->
            val folderId = "branch-$i"
            remote.seedFolder(folderId, "cloud-root", "branch$i")
            remote.seedFile("leaf-$i", folderId, "index.html", "b$i".toByteArray())
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(24)
        repeat(24) { i ->
            assertThat(String(fs.readFile(root, "branch$i/index.html"))).isEqualTo("b$i")
        }
    }

    /**
     * Graph cycle / re-parent alias must not unbounded-walk: each remote id listed once.
     */
    @Test
    fun forcePull_remoteCycle_doesNotUnboundedWalk() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("a", "cloud-root", "a")
        inner.seedFolder("b", "a", "b")
        inner.seedFile("leaf", "b", "ok.txt", "ok".toByteArray())

        val listCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                listCalls.incrementAndGet()
                val kids = inner.listChildrenPaged(parentId, includeValues).toMutableList()
                // Cycle edge: listing b also returns already-visited folder a.
                if (parentId == "b") {
                    kids.add(
                        ru.protolink.communicator.sync.model.RemoteEntry(
                            id = "a",
                            parentId = "b",
                            name = "a-again",
                            isFolder = true
                        )
                    )
                }
                return kids
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "a/b/ok.txt"))).isEqualTo("ok")
        // root + a + b only (cycle edge skipped by visitedIds) — not exploding.
        assertThat(listCalls.get()).isAtMost(4)
    }

    @Test
    fun walkRemote_concurrencyConstant_matchesWindowsSyncLane() {
        assertThat(SyncEngine.WALK_REMOTE_CONCURRENCY).isEqualTo(24)
    }

    @Test
    fun inventoryFetch_detectsHttp500AsTransient_not401() {
        assertThat(
            SyncInventoryFetch.isTransientServerError(
                RuntimeException("HTTP 500 Internal Server Error")
            )
        ).isTrue()
        assertThat(
            SyncInventoryFetch.isTransientServerError(
                RuntimeException("retrofit2.HttpException: HTTP 503 Service Unavailable")
            )
        ).isTrue()
        assertThat(
            SyncInventoryFetch.isHardClientError(RuntimeException("HTTP 401 Unauthorized"))
        ).isTrue()
        assertThat(
            SyncInventoryFetch.isTransientServerError(RuntimeException("HTTP 401 Unauthorized"))
        ).isFalse()
    }

    /**
     * FAIL class P1-20261117 structure-first HTTP 500: one mid-walk GetEntities 500 must
     * retry then succeed (or soft-fail that branch) — whole Force must still fill siblings.
     */
    @Test
    fun forcePull_listChildrenHttp500Once_retriesAndFills() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())

        val filmsFailsLeft = java.util.concurrent.atomic.AtomicInteger(1)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentId == "films" && filmsFailsLeft.getAndDecrement() > 0) {
                    throw RuntimeException("HTTP 500 Internal Server Error")
                }
                return inner.listChildrenPaged(parentId, includeValues)
            }

            // Must override: `by inner` would call InMemory default and skip this 500 hook.
            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> =
                parentIds.flatMap { listChildrenPaged(it, includeValues) }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
    }

    @Test
    fun forcePull_listChildrenHttp500ExhaustedOnBranch_softFailsContinuesSibling() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("bad", "cloud-root", "bad")
        inner.seedFolder("films", "cloud-root", "films")
        inner.seedFile("leaf-bad", "bad", "gone.txt", "x".toByteArray())
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentId == "bad") {
                    throw RuntimeException("HTTP 500 Internal Server Error")
                }
                return inner.listChildrenPaged(parentId, includeValues)
            }

            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> =
                parentIds.flatMap { listChildrenPaged(it, includeValues) }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(fs.exists(root, "bad/gone.txt")).isFalse()
    }

    @Test
    fun forcePull_listChildrenHttp401_stillAborts() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFile("ok", "cloud-root", "ok.txt", "ok".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentId == "cloud-root") {
                    throw RuntimeException("HTTP 401 Unauthorized")
                }
                return inner.listChildrenPaged(parentId, includeValues)
            }
        }

        try {
            SyncEngine(store, fs, remote).forcePullMapping(mapping)
            throw AssertionError("expected HTTP 401 to abort Force")
        } catch (t: Throwable) {
            assertThat(t.message.orEmpty()).contains("401")
        }
    }

    /**
     * Warm WalkRemote (Win values-light): reconcile with every child id already in meta must
     * reuse structure-only listings (includeValues=false) — no Values re-list. Force stays cold.
     */
    @Test
    fun reconcile_warmMeta_usesStructureOnlyListings() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        // Prior sync meta: warm path reuses RelativePath names from structure-only lists.
        store.upsert(
            ru.protolink.communicator.sync.model.SyncItemMeta(
                mappingId = "m1",
                remoteId = "films",
                parentRemoteId = "cloud-root",
                relativePath = "Films",
                isFolder = true,
                sizeBytes = 0,
                contentHash = "",
                remoteUpdateTime = java.time.Instant.now()
            )
        )
        store.upsert(
            ru.protolink.communicator.sync.model.SyncItemMeta(
                mappingId = "m1",
                remoteId = "idx",
                parentRemoteId = "films",
                relativePath = "Films/index.html",
                isFolder = false,
                sizeBytes = 5,
                contentHash = "x",
                remoteUpdateTime = java.time.Instant.now()
            )
        )
        fs.writeFile(root, "Films/index.html", "movie".toByteArray())

        val withValues = java.util.concurrent.atomic.AtomicInteger(0)
        val withoutValues = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues) withValues.incrementAndGet() else withoutValues.incrementAndGet()
                return inner.listChildrenPaged(parentId, includeValues).map {
                    if (!includeValues) it.copy(name = if (it.isFolder) "folder-${it.id}" else "file-${it.id}")
                    else it
                }
            }
        }

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(withoutValues.get()).isAtLeast(1)
        assertThat(withValues.get()).isEqualTo(0)
    }

    /**
     * FAIL class P1-20261117 inventory still unbounded: Force must batch many parents per
     * GetEntities (ParentIdsMatchAny) so GE count ≪ folder count on wide trees.
     */
    @Test
    fun forcePull_batchesParentListings_geCallsLessThanFolderCount() = runTest {
        val (store, fs, inner) = harness()
        val folderCount = 60
        for (i in 0 until folderCount) {
            inner.seedFolder("f$i", "cloud-root", "Folder$i")
            inner.seedFile("file$i", "f$i", "n.txt", "x$i".toByteArray())
        }

        val parentBatchCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val singleParentCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                singleParentCalls.incrementAndGet()
                return inner.listChildrenPaged(parentId, includeValues)
            }

            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                parentBatchCalls.incrementAndGet()
                return parentIds.flatMap { pid ->
                    inner.listChildrenPaged(pid, includeValues)
                }
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(folderCount)
        // Root alone (1 single) + ≥1 batched multi-parent listing; never 1+60 singles for structure.
        assertThat(parentBatchCalls.get()).isAtLeast(1)
        assertThat(singleParentCalls.get()).isLessThan(folderCount)
        assertThat(fs.exists(root, "Folder0/n.txt")).isTrue()
        assertThat(fs.exists(root, "Folder59/n.txt")).isTrue()
    }

    /**
     * Force after heal: structure-only parent walk (ignore stale meta) + Ids IncludeValues
     * for names — never warm values-light double-list, never IncludeValues×folder cold walk.
     */
    @Test
    fun forcePull_withStaleMeta_structureFirstThenIdsNames() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        // Wrong-tree ghost meta (heal class): ids not on the new cloud folder.
        store.upsert(
            ru.protolink.communicator.sync.model.SyncItemMeta(
                mappingId = "m1",
                remoteId = "ghost-confluence",
                parentRemoteId = "cloud-root",
                relativePath = "confluence",
                isFolder = true,
                sizeBytes = 0,
                contentHash = "",
                remoteUpdateTime = java.time.Instant.now()
            )
        )

        val parentWithValues = java.util.concurrent.atomic.AtomicInteger(0)
        val parentWithoutValues = java.util.concurrent.atomic.AtomicInteger(0)
        val idsWithValues = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues) parentWithValues.incrementAndGet() else parentWithoutValues.incrementAndGet()
                return inner.listChildrenPaged(parentId, includeValues).map {
                    if (!includeValues) it.copy(name = if (it.isFolder) "folder-${it.id}" else "file-${it.id}")
                    else it
                }
            }

            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues) idsWithValues.incrementAndGet()
                return inner.getEntitiesByIds(ids, includeValues)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        // Structure-first: parent listings are includeValues=false only (root + Films).
        assertThat(parentWithoutValues.get()).isEqualTo(2)
        assertThat(parentWithValues.get()).isEqualTo(0)
        // Names come from Ids IncludeValues batches — not per-folder Values re-list.
        assertThat(idsWithValues.get()).isAtLeast(1)
        // Ghost meta path must not remain after Force prune.
        assertThat(fs.exists(root, "confluence")).isFalse()
    }

    /**
     * ForceIdsNames must not fire fat IncludeValues pages (live 200 → HTTP 504).
     * Chunks stay ≤ FORCE_NAME_BATCH so gateway budget is reachable.
     */
    @Test
    fun forcePull_idsNames_usesSmallBatches() = runTest {
        val (store, fs, inner) = harness()
        val folderCount = SyncEngine.FORCE_NAME_BATCH + 10
        for (i in 0 until folderCount) {
            inner.seedFolder("f$i", "cloud-root", "Folder$i")
            inner.seedFile("n$i", "f$i", "n.txt", "x".toByteArray())
        }
        val maxIdsChunk = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues) {
                    maxIdsChunk.accumulateAndGet(ids.size) { a, b -> maxOf(a, b) }
                }
                return inner.getEntitiesByIds(ids, includeValues)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(folderCount)
        assertThat(maxIdsChunk.get()).isAtMost(SyncEngine.FORCE_NAME_BATCH)
        assertThat(maxIdsChunk.get()).isGreaterThan(0)
        assertThat(fs.exists(root, "Folder0/n.txt")).isTrue()
        assertThat(fs.exists(root, "Folder${folderCount - 1}/n.txt")).isTrue()
    }

    /**
     * Live ForceIdsNames class: fat Ids IncludeValues → HTTP 504 → soft-fail empty names →
     * no downloadFile. Half-split on exhausted 5xx must still recover names + fill.
     */
    @Test
    fun forcePull_idsNames_http504_splitsAndFills() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("agents", "cloud-root", "Agents")
        inner.seedFile("note", "agents", "note.html", "n".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues && ids.size > 1) {
                    throw RuntimeException("HTTP 504 Gateway Time-out")
                }
                return inner.getEntitiesByIds(ids, includeValues)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "Agents/note.html"))).isEqualTo("n")
    }

    /**
     * 1.0.54 slow-nodl class: waited for ALL name Ids before any downloadFile.
     * Progressive BFS must download shallow files before deep Ids name resolve.
     */
    @Test
    fun forcePull_progressive_downloadsBeforeDeepNames() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("d1", "cloud-root", "Deep")
        inner.seedFolder("d2", "d1", "Nested")
        inner.seedFile("deep", "d2", "file.txt", "x".toByteArray())

        val deepNameResolved = java.util.concurrent.atomic.AtomicBoolean(false)
        val filmsDownloadedBeforeDeepName = java.util.concurrent.atomic.AtomicBoolean(false)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                // Leaf under Nested — named one BFS wave after Films/index materialize.
                if (includeValues && ids.contains("deep")) {
                    deepNameResolved.set(true)
                }
                return inner.getEntitiesByIds(ids, includeValues)
            }

            override suspend fun downloadFile(entityId: String): ByteArray? {
                if (entityId == "idx" && !deepNameResolved.get()) {
                    filmsDownloadedBeforeDeepName.set(true)
                }
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(filmsDownloadedBeforeDeepName.get()).isTrue()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "Deep/Nested/file.txt"))).isEqualTo("x")
    }

    /**
     * Live 1.0.56 progressive-incomplete: ghost prune waited for full tree Force-complete.
     * After root wave names, wrong-tree root dirs must die before deeper downloads finish.
     */
    @Test
    fun forcePull_earlyPrunesRootGhostsBeforeDeepDownloads() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("deep", "cloud-root", "Deep")
        inner.seedFile("deepf", "deep", "file.txt", "x".toByteArray())
        fs.createDirectory(root, "confluence")
        fs.writeFile(root, "confluence/page.html", "ghost".toByteArray())
        fs.createDirectory(root, "databases")

        val deepDownloaded = java.util.concurrent.atomic.AtomicBoolean(false)
        val ghostGoneBeforeDeep = java.util.concurrent.atomic.AtomicBoolean(false)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                if (entityId == "deepf") {
                    ghostGoneBeforeDeep.set(
                        !fs.exists(root, "confluence") && !fs.exists(root, "databases")
                    )
                    deepDownloaded.set(true)
                }
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(deepDownloaded.get()).isTrue()
        assertThat(ghostGoneBeforeDeep.get()).isTrue()
        assertThat(fs.exists(root, "confluence")).isFalse()
        assertThat(fs.exists(root, "databases")).isFalse()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
    }

    /** AC folders (films/agentteamverify) materialize before lower-priority sibling trees. */
    @Test
    fun forcePull_priorityMaterializesFilmsBeforeWideSibling() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("wide", "cloud-root", "WideTree")
        inner.seedFile("w1", "wide", "a.txt", "w".toByteArray())
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())

        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                order.add(entityId)
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(order.indexOf("idx")).isLessThan(order.indexOf("w1"))
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
    }

    /**
     * Meta RelativePath leaf names seed ForceIdsNames — skip IncludeValues for known ids.
     */
    @Test
    fun forcePull_metaNameCache_skipsIdsIncludeValues() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        store.upsert(
            ru.protolink.communicator.sync.model.SyncItemMeta(
                mappingId = "m1",
                remoteId = "films",
                parentRemoteId = "cloud-root",
                relativePath = "Films",
                isFolder = true,
                sizeBytes = 0,
                contentHash = "",
                remoteUpdateTime = java.time.Instant.now()
            )
        )
        store.upsert(
            ru.protolink.communicator.sync.model.SyncItemMeta(
                mappingId = "m1",
                remoteId = "idx",
                parentRemoteId = "films",
                relativePath = "Films/index.html",
                isFolder = false,
                sizeBytes = 5,
                contentHash = "",
                remoteUpdateTime = java.time.Instant.now()
            )
        )
        val idsWithValues = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (includeValues) idsWithValues.incrementAndGet()
                return inner.getEntitiesByIds(ids, includeValues)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(idsWithValues.get()).isEqualTo(0)
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
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
            override suspend fun listChildrenPaged(parentId: String, includeValues: Boolean) =
                inner.listChildrenPaged(parentId, includeValues).map { it.copy(sizeBytes = null) }
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
            override suspend fun listChildrenPaged(parentId: String, includeValues: Boolean) =
                inner.listChildrenPaged(parentId, includeValues).map { it.copy(sizeBytes = null) }
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

    @Test
    fun bootstrap_remoteFoldersAndFiles_materializeLocally() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("child-a", "cloud-root", "Projects")
        remote.seedFolder("child-b", "child-a", "Notes")
        remote.seedFile("note-1", "child-b", "index.html", "<p>hello</p>".toByteArray())

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "projects")).isTrue()
        assertThat(fs.exists(root, "projects/notes")).isTrue()
        assertThat(fs.exists(root, "projects/notes/index.html")).isTrue()
        assertThat(String(fs.readFile(root, "projects/notes/index.html"))).isEqualTo("<p>hello</p>")
        assertThat(store.getAll("m1").any { it.relativePath == "projects" && it.isFolder }).isTrue()
        assertThat(store.getAll("m1").any { it.relativePath == "projects/notes" && it.isFolder }).isTrue()
    }

    @Test
    fun remoteAdd_folder_materializesAfterBootstrap() = runTest {
        val (store, fs, remote) = harness()
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        remote.seedFolder("new-folder", "cloud-root", "Inbox")
        remote.seedFile("inbox-note", "new-folder", "readme.txt", "hi".toByteArray())

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "inbox")).isTrue()
        assertThat(String(fs.readFile(root, "inbox/readme.txt"))).isEqualTo("hi")
    }

    @Test
    fun materializeCreateFails_softFailsAndContinuesSibling() = runTest {
        val (store, innerFs, remote) = harness()
        remote.seedFolder("bad-folder", "cloud-root", "Broken")
        remote.seedFolder("ok-folder", "cloud-root", "Good")
        remote.seedFile("ok-file", "ok-folder", "ok.txt", "ok-bytes".toByteArray())

        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun createDirectory(rootPath: String, relativePath: String) {
                if (PathUtil.normalize(relativePath) == "broken") {
                    error("SAF create failed for broken")
                }
                innerFs.createDirectory(rootPath, relativePath)
            }
        }

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "broken")).isFalse()
        assertThat(fs.exists(root, "good")).isTrue()
        assertThat(String(fs.readFile(root, "good/ok.txt"))).isEqualTo("ok-bytes")
        assertThat(remote.get("bad-folder")).isNotNull()
        assertThat(remote.get("ok-folder")).isNotNull()
    }

    /**
     * FAIL class from P1-20261115-verify-mynotesfolder-sync-android: partial bootstrap left
     * folder meta + empty local dirs; next Sync must fill remote files (heal folders-only).
     */
    @Test
    fun folderOnlyMeta_healsMissingRemoteFilesOnNextSync() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "<p>Movie</p>".toByteArray())
        remote.seedFolder("atv-f", "cloud-root", "agentteamverify")
        remote.seedFile("atv-i", "atv-f", "probe-test.txt", "fixture".toByteArray())

        fs.createDirectory(root, "films")
        fs.createDirectory(root, "agentteamverify")
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "films-f",
                parentRemoteId = "cloud-root",
                relativePath = "films",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "atv-f",
                parentRemoteId = "cloud-root",
                relativePath = "agentteamverify",
                isFolder = true,
                sizeBytes = 0
            )
        )

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("<p>Movie</p>")
        assertThat(String(fs.readFile(root, "agentteamverify/probe-test.txt"))).isEqualTo("fixture")
    }

    /** Bootstrap must not call full-tree snapshot once per remote item (O(n²) on polluted roots). */
    @Test
    fun bootstrap_doesNotSnapshotWholeTreePerRemoteItem() = runTest {
        val (store, innerFs, remote) = harness()
        var snapshots = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(rootPath: String): List<FsEntry> {
                snapshots++
                return innerFs.snapshot(rootPath)
            }
        }
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        repeat(25) { i ->
            remote.seedFile("r$i", "cloud-root", "cloud-$i.txt", "c$i".toByteArray())
        }
        repeat(40) { i ->
            innerFs.writeFile(root, "local-junk-$i.bin", ByteArray(2048) { i.toByte() })
        }

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        // recomputeFolderSizes (1) + compare (1) + local (1) ≈ 3; old bug was 26+ during bootstrap alone.
        assertThat(snapshots).isAtMost(8)
    }

    /** Remote children fill even when local junk would otherwise dominate the pass. */
    @Test
    fun remoteMissingFiles_materializeDespiteLocalJunk() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "from-cloud".toByteArray())
        fs.createDirectory(root, "films")
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "films-f",
                parentRemoteId = "cloud-root",
                relativePath = "films",
                isFolder = true,
                sizeBytes = 0
            )
        )
        repeat(15) { i ->
            fs.writeFile(root, "evidence-$i.png", ByteArray(512) { 1 })
        }

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("from-cloud")
        // Cloud-wins: junk absent from remote is pruned (not uploaded) on full Sync.
        assertThat(fs.exists(root, "evidence-0.png")).isFalse()
    }

    /** Sync removes cloud-absent local dirs when remote tree is non-empty (delete-ghost). */
    @Test
    fun reconcile_prunesCloudAbsentLocalWhenRemoteNonEmpty() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        fs.writeFile(root, "tor browser/readme.txt", "junk".toByteArray())
        fs.createDirectory(root, "confluence")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(fs.exists(root, "tor browser")).isFalse()
        assertThat(fs.exists(root, "confluence")).isFalse()
        assertThat(remote.childrenOf("cloud-root").none { it.name.equals("tor browser", true) }).isTrue()
    }

    /** Empty remote still allows local-only upload (first map / push path). */
    @Test
    fun reconcile_emptyRemote_stillUploadsLocalOnly() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "solo.txt", "only-local".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(fs.exists(root, "solo.txt")).isTrue()
        assertThat(remote.childrenOf("cloud-root").any { it.name.equals("solo.txt", true) }).isTrue()
    }
}
