package ru.protolink.communicator.sync.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
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

    /**
     * Three-way upload: nested local-new folder+file must create parents first (Win OrderBy depth),
     * then child under that parent — not deepest-first leftover from removed PruneCloudAbsent.
     */
    @Test
    fun localAdd_nestedFolder_uploadsUnderParent() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFile("keep", "cloud-root", "keep.txt", "k".toByteArray())
        fs.writeFile(root, "keep.txt", "k".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        fs.createDirectory(root, "at-sync-threeway-upload-test")
        fs.writeFile(root, "at-sync-threeway-upload-test/readme-test.txt", "threeway-upload".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        val folderMeta = store.getAll("m1").firstOrNull {
            PathUtil.normalize(it.relativePath) == "at-sync-threeway-upload-test" && it.isFolder
        }
        assertThat(folderMeta).isNotNull()
        val fileMeta = store.getAll("m1").firstOrNull {
            PathUtil.normalize(it.relativePath) == "at-sync-threeway-upload-test/readme-test.txt"
        }
        assertThat(fileMeta).isNotNull()
        assertThat(fileMeta!!.parentRemoteId).isEqualTo(folderMeta!!.remoteId)
        assertThat(
            remote.childrenOf("cloud-root").any { it.name.equals("at-sync-threeway-upload-test", true) }
        ).isTrue()
        assertThat(
            remote.childrenOf(folderMeta.remoteId).any { it.name.equals("readme-test.txt", true) }
        ).isTrue()
    }

    /** Three-way download: Api-only child materializes on local (ApplyRemote Added). */
    @Test
    fun remoteAdd_folder_downloadsOnWarmReconcile() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFile("keep", "cloud-root", "keep.txt", "k".toByteArray())
        fs.writeFile(root, "keep.txt", "k".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        remote.seedFolder("dl-f", "cloud-root", "at-sync-threeway-download-test")
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(
            store.getAll("m1").any {
                PathUtil.normalize(it.relativePath) == "at-sync-threeway-download-test" && it.isFolder
            }
        ).isTrue()
    }

    /**
     * Tip 1.0.119: progressive Walk materializes map-root Api-only kids before deep sibling
     * BFS completes (1.0.118 DOWNLOAD sticky Syncing / late SAF). Wide remote tree must not
     * starve the download fixture.
     */
    @Test
    fun reconcile_progressiveWalk_materializesRootApiOnlyBesideWideTree() = runTest {
        val (store, fs, remote) = harness()
        val keepBytes = "keep-body".toByteArray()
        remote.seedFile("keep-f", "cloud-root", "keep.txt", keepBytes)
        fs.writeFile(root, "keep.txt", keepBytes)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        // Wide sibling tree (phone Walk cost class) + Api-only download at map root.
        for (i in 0 until 40) {
            remote.seedFolder("wide-$i", "cloud-root", "wide-branch-$i")
            remote.seedFolder("wide-$i-child", "wide-$i", "child")
            remote.seedFile(
                "wide-$i-idx",
                "wide-$i-child",
                "index.html",
                "wide-$i".toByteArray()
            )
        }
        remote.seedFolder("dl-f", "cloud-root", "at-sync-threeway-download-test")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(
            store.getAll("m1").any {
                it.remoteId == "dl-f" &&
                    PathUtil.normalize(it.relativePath) == "at-sync-threeway-download-test"
            }
        ).isTrue()
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
        // Progressive dirty must not DeleteEntity the same-pass download.
        assertThat(remote.get("dl-f")).isNotNull()
    }

    /**
     * Tip 1.0.120: phantom progressive mkdir must not upsert meta — ApplyRemote retries
     * (1.0.119 DOWNLOAD lateSAF=NO when meta claimed land without SAF).
     */
    @Test
    fun reconcile_progressiveWalk_phantomMkdir_fallsThroughToApplyRemote() = runTest {
        val (store, innerFs, remote) = harness()
        val keepBytes = "keep-body".toByteArray()
        remote.seedFile("keep-f", "cloud-root", "keep.txt", keepBytes)
        innerFs.writeFile(root, "keep.txt", keepBytes)
        SyncEngine(store, innerFs, remote).reconcileAll(listOf(mapping))

        remote.seedFolder("dl-f", "cloud-root", "at-sync-threeway-download-test")
        var phantomAttempts = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun createDirectory(rootPath: String, relativePath: String) {
                val n = PathUtil.normalize(relativePath)
                if (n == "at-sync-threeway-download-test" && phantomAttempts == 0) {
                    phantomAttempts++
                    // Progressive Walk: pretend mkdir succeeded but path missing.
                    return
                }
                innerFs.createDirectory(rootPath, relativePath)
            }
        }
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(phantomAttempts).isEqualTo(1)
        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(
            store.getAll("m1").any {
                it.remoteId == "dl-f" &&
                    PathUtil.normalize(it.relativePath) == "at-sync-threeway-download-test"
            }
        ).isTrue()
    }

    /**
     * Tip 1.0.121: ApplyRemote Added must not upsert meta on phantom mkdir when progressive
     * is off (bootstrap / Force-class path) — else warm Walk treats id as known and DOWNLOAD
     * never retries (1.0.120 lateSAF=NO class).
     */
    @Test
    fun reconcile_applyRemote_phantomMkdir_doesNotClaimMeta() = runTest {
        val (store, innerFs, remote) = harness()
        remote.seedFolder("dl-f", "cloud-root", "at-sync-threeway-download-test")
        var phantomAttempts = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun createDirectory(rootPath: String, relativePath: String) {
                val n = PathUtil.normalize(relativePath)
                if (n == "at-sync-threeway-download-test") {
                    phantomAttempts++
                    return
                }
                innerFs.createDirectory(rootPath, relativePath)
            }
        }
        // Cold bootstrap uses progressiveMaterialize=false — hits ApplyRemote Added only.
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(phantomAttempts).isAtLeast(1)
        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isFalse()
        assertThat(store.getAll("m1").any { it.remoteId == "dl-f" }).isFalse()
    }

    /**
     * Tip 1.0.120: Sync-first local delete must still DeleteEntity after progressive Walk
     * (1.0.119 soft full budget aborted before ApplyLocal Removed).
     */
    @Test
    fun reconcile_progressiveWalk_localRemoveStillDeletesRemote() = runTest {
        val (store, fs, remote) = harness()
        val keepBytes = "keep-body".toByteArray()
        remote.seedFile("keep-f", "cloud-root", "keep.txt", keepBytes)
        fs.writeFile(root, "keep.txt", keepBytes)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        for (i in 0 until 20) {
            remote.seedFolder("wide-$i", "cloud-root", "wide-branch-$i")
        }
        fs.createDirectory(root, "at-sync-threeway-delete-test")
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val deleteId = store.getAll("m1").first {
            PathUtil.normalize(it.relativePath) == "at-sync-threeway-delete-test"
        }.remoteId

        fs.delete(root, "at-sync-threeway-delete-test", true)
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(remote.get(deleteId)).isNull()
        assertThat(
            store.getAll("m1").none {
                PathUtil.normalize(it.relativePath) == "at-sync-threeway-delete-test"
            }
        ).isTrue()
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
    fun sameSizeContentChange_reconcileSkips_forcePushUploads() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "aaaa".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val id = store.getAll("m1").first { it.relativePath == "films/index.html" }.remoteId
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("aaaa")

        // Win CanSkipCompareWithoutHash: warm reconcile skips same-size silent edits
        // (interval Push / Force Upload own them).
        fs.writeFile(root, "films/index.html", "bbbb".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("aaaa")

        SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(String(remote.get(id)!!.bytes!!)).isEqualTo("bbbb")
        assertThat(store.getAll("m1").first { it.relativePath == "films/index.html" }.sizeBytes)
            .isEqualTo(4)
    }

    @Test
    fun emptyContentHash_seedsLocalHash_thenForcePushSameSizeEdit() = runTest {
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
        SyncEngine(store, fs, remote).forcePushMapping(mapping)
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
        // sizeBytes mismatch defeats CanSkipCompareWithoutHash so compare still downloads.
        store.upsert(
            store.getAll("m1").first { it.relativePath == "keep.html" }.copy(
                contentHash = ContentHashUtil.sha256Hex(keepRemoteOld.toByteArray()),
                sizeBytes = 1,
                remoteUpdateTime = Instant.EPOCH
            )
        )
        store.upsert(
            store.getAll("m1").first { it.relativePath == "fail.html" }.copy(
                contentHash = "bb",
                sizeBytes = 1,
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
        // sizeBytes mismatch so warm CanSkipCompareWithoutHash does not skip empty-remote decide.
        store.upsert(
            store.getAll("m1").first { it.relativePath == "note/index.html" }.copy(
                contentHash = "aa",
                sizeBytes = 1,
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
    fun localMatchesMeta_remoteChanged_forcePullDownloads() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "films/index.html", "Test4".toByteArray())
        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        val meta = store.getAll("m1").first { it.relativePath == "films/index.html" }
        val id = meta.remoteId
        // Other device uploaded Test5; warm WalkRemote nulls UpdateTime so reconcile skips
        // getFile (Win 1.0.77). Force Download is the repair path.
        remote.uploadFile(id, "index.html", "Test5".toByteArray(), "text/html")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("Test4")

        SyncEngine(store, fs, remote).forcePullMapping(mapping)

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
        // Not "Agents" — Force push skips whole Agents tree (1.0.123 SAF churn / sticky).
        val existingFolder = remote.addFolder("cloud-root", "SharedDocs")
        fs.createDirectory(root, "SharedDocs")
        fs.writeFile(root, "SharedDocs/note.txt", "x".toByteArray())
        SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(
            remote.childrenOf("cloud-root").filter { it.isFolder && it.name.equals("SharedDocs", true) }
        ).hasSize(1)
        assertThat(store.getAll("m1").first { it.relativePath == "shareddocs" }.remoteId)
            .isEqualTo(existingFolder)
    }

    /** Force upload must not walk/upload Agents tree (Sync Walk stop; 1.0.122 FORCE sticky). */
    @Test
    fun forcePush_skipsAgentsTree_uploadsMapRootOnly() = runTest {
        val (store, fs, remote) = harness()
        fs.writeFile(root, "plant-test.txt", "ok".toByteArray())
        fs.createDirectory(root, "Agents")
        fs.writeFile(root, "Agents/roles/todo/x.txt", "churn".toByteArray())
        val count = SyncEngine(store, fs, remote).forcePushMapping(mapping)
        assertThat(count).isEqualTo(1)
        assertThat(remote.childrenOf("cloud-root").any {
            it.name.equals("plant-test.txt", ignoreCase = true)
        }).isTrue()
        assertThat(remote.childrenOf("cloud-root").none {
            it.isFolder && it.name.equals("Agents", ignoreCase = true)
        }).isTrue()
        assertThat(store.getAll("m1").none {
            AgentsRoleQueuePaths.isUnderAgentsTree(it.relativePath)
        }).isTrue()
    }

    @Test
    fun forcePull_overwritesLocal() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "aa".toByteArray())
        fs.writeFile(root, "films/index.html", "LOCAL".toByteArray())
        SyncEngine(store, fs, remote).forcePullMapping(mapping)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("aa")
        assertThat(store.getAll("m1").first { it.relativePath == "films/index.html" }.contentHash)
            .isEqualTo(ContentHashUtil.sha256Hex("aa".toByteArray()))
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
    fun agentsRoleQueuePaths_protectsRoleQueues_includingDone() {
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/roles/frontend-developer/todo/P1-task/index.html"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/roles/frontend-developer/done/P1-old/index.html"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/docs/ops/index.html"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/roles/fe/todo/ghost-shell"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/roles/fe/done/P1-old"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/roles/fe/todo"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/roles/fe/done"
            )
        ).isFalse()
    }

    @Test
    fun shouldSkipLocalPush_matchesAgentsQueues_notDocs() {
        // Win SyncAutopushAgentsChurnTests — AutoPush exclude class (1.0.118).
        assertThat(
            AgentsRoleQueuePaths.shouldSkipLocalPush(
                "Protolink.ru/Agents/roles/frontend-developer/todo/P0-x/index.html"
            )
        ).isTrue()
        assertThat(AgentsRoleQueuePaths.shouldSkipLocalPush("Protolink.ru/Agents")).isTrue()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipLocalPush(
                "Protolink.ru/Agents/inbox/todo/ask/index.html"
            )
        ).isTrue()
        assertThat(AgentsRoleQueuePaths.shouldSkipLocalPush("notes/keep.txt")).isFalse()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipLocalPush("Protolink.ru/Agents/docs/ops/index.html")
        ).isFalse()
    }

    @Test
    fun pushLocal_agentsQueueChurn_doesNotUpload_andStampsLastSync() = runTest {
        // Win SyncAutopushAgentsChurnTests.PushLocal_AgentsQueueChurn_DoesNotUpload_AndStampsLastSync
        val (store, fs, remote) = harness()
        fs.createDirectory(root, "notes")
        fs.writeFile(root, "notes/keep.txt", "stable-keep".toByteArray())
        // Agents at map root (same skip class as Protolink.ru/Agents/…).
        val agentsTask = "Agents/roles/frontend-developer/todo/P0-churn-task/index.html"
        fs.createDirectory(root, PathUtil.parentOf(agentsTask))
        fs.writeFile(root, agentsTask, "agents-v1".toByteArray())
        val keepBytes = "stable-keep".toByteArray()
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "notes-f",
                parentRemoteId = "cloud-root",
                relativePath = "notes",
                isFolder = true,
                sizeBytes = keepBytes.size.toLong()
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep-f",
                parentRemoteId = "notes-f",
                relativePath = "notes/keep.txt",
                isFolder = false,
                sizeBytes = keepBytes.size.toLong(),
                contentHash = ContentHashUtil.sha256Hex(keepBytes)
            )
        )

        repeat(3) { i ->
            fs.writeFile(root, agentsTask, "agents-v${i + 2}".toByteArray())
            val applied = SyncEngine(store, fs, remote).pushLocalChanges(listOf(mapping))
            assertThat(applied).isEqualTo(0)
            assertThat(
                remote.childrenOf("cloud-root").none {
                    it.name.contains("P0-churn-task", ignoreCase = true) ||
                        it.name.equals("Agents", ignoreCase = true)
                }
            ).isTrue()
        }
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
    }

    @Test
    fun pushLocal_deleteFixture_notBlockedByAgentsChurn() = runTest {
        // Sibling three-way delete must still DeleteEntity while Agents queues churn.
        val (store, fs, remote) = harness()
        remote.seedFolder("del-f", "cloud-root", "at-sync-threeway-delete-test")
        fs.createDirectory(root, "notes")
        fs.writeFile(root, "notes/keep.txt", "stable".toByteArray())
        val agentsTask = "Agents/roles/frontend-developer/todo/P0-churn/index.html"
        fs.createDirectory(root, PathUtil.parentOf(agentsTask))
        fs.writeFile(root, agentsTask, "churn".toByteArray())
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "notes-f",
                parentRemoteId = "cloud-root",
                relativePath = "notes",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep-f",
                parentRemoteId = "notes-f",
                relativePath = "notes/keep.txt",
                isFolder = false,
                sizeBytes = 6,
                contentHash = ContentHashUtil.sha256Hex("stable".toByteArray())
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "del-f",
                parentRemoteId = "cloud-root",
                relativePath = "at-sync-threeway-delete-test",
                isFolder = true,
                sizeBytes = 0
            )
        )
        // Local gone → Removed; Agents dirty must not starve DeleteEntity.
        fs.writeFile(root, agentsTask, "churn-v2".toByteArray())
        val applied = SyncEngine(store, fs, remote).pushLocalChanges(listOf(mapping))
        assertThat(applied).isAtLeast(1)
        assertThat(remote.get("del-f")).isNull()
        assertThat(store.getAll("m1").none { it.remoteId == "del-f" }).isTrue()
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
    }

    @Test
    fun reconcile_agentsQueueChurn_stillDownloadsApiOnly_andDeletesLocalRemoved() = runTest {
        // Tip 1.0.115+: sticky Syncing after 1.0.114 — Agents exclude on Sync
        // Walk/ApplyRemote/ApplyLocal so Api-only download + local-delete converge.
        val (store, fs, remote) = harness()
        val keepBytes = "stable-keep".toByteArray()
        val dlBytes = "api-only-download".toByteArray()
        remote.seedFile("keep-f", "cloud-root", "keep.txt", keepBytes)
        remote.seedFile("dl-f", "cloud-root", "at-sync-threeway-download-test", dlBytes)
        remote.seedFolder("del-f", "cloud-root", "at-sync-threeway-delete-test")
        // Agents cloud churn under map root (unknown kids would IncludeValues forever).
        remote.seedFolder("agents-root", "cloud-root", "Agents")
        remote.seedFolder("agents-roles", "agents-root", "roles")
        remote.seedFolder("agents-fe", "agents-roles", "frontend-developer")
        remote.seedFolder("agents-todo", "agents-fe", "todo")
        remote.seedFolder("agents-task", "agents-todo", "P0-churn-shell")
        remote.seedFile(
            "agents-idx",
            "agents-task",
            "index.html",
            "agents-cloud-v1".toByteArray()
        )
        fs.writeFile(root, "keep.txt", keepBytes)
        // Local Agents present (must not be wiped when Walk skips Agents BFS).
        val agentsLocal = "Agents/roles/frontend-developer/todo/P0-churn-shell/index.html"
        fs.createDirectory(root, PathUtil.parentOf(agentsLocal))
        fs.writeFile(root, agentsLocal, "agents-local".toByteArray())
        // Delete fixture: meta+remote exist; local gone → ApplyLocal Removed → DeleteEntity.
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep-f",
                parentRemoteId = "cloud-root",
                relativePath = "keep.txt",
                isFolder = false,
                sizeBytes = keepBytes.size.toLong(),
                contentHash = ContentHashUtil.sha256Hex(keepBytes)
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "del-f",
                parentRemoteId = "cloud-root",
                relativePath = "at-sync-threeway-delete-test",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-root",
                parentRemoteId = "cloud-root",
                relativePath = "Agents",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-idx",
                parentRemoteId = "agents-task",
                relativePath = agentsLocal,
                isFolder = false,
                sizeBytes = 12,
                contentHash = ContentHashUtil.sha256Hex("agents-local".toByteArray())
            )
        )

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(String(fs.readFile(root, "at-sync-threeway-download-test"))).isEqualTo("api-only-download")
        assertThat(remote.get("del-f")).isNull()
        assertThat(store.getAll("m1").none { it.remoteId == "del-f" }).isTrue()
        assertThat(fs.exists(root, agentsLocal)).isTrue()
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
    }

    @Test
    fun reconcile_warmAgentsDocsMeta_doesNotFalseRemove_stillDownloadsAndDeletes() = runTest {
        // Tip 1.0.116 live shape (1.0.115 FAIL): Walk stops at Agents; warm meta under
        // Agents/docs/** is NOT queue-protected — must not SAF-delete before Api-only Added.
        val (store, fs, remote) = harness()
        val keepBytes = "stable-keep".toByteArray()
        val dlBytes = "api-only-download-116".toByteArray()
        val docsBytes = "agents-docs-body".toByteArray()
        remote.seedFile("keep-f", "cloud-root", "keep.txt", keepBytes)
        remote.seedFile("dl-f", "cloud-root", "at-sync-threeway-download-test", dlBytes)
        remote.seedFolder("del-f", "cloud-root", "at-sync-threeway-delete-test")
        remote.seedFolder("agents-root", "cloud-root", "Agents")
        // Cloud also has docs (Walk will not descend; meta is warm from prior full walk).
        remote.seedFolder("agents-docs", "agents-root", "docs")
        remote.seedFolder("agents-ops", "agents-docs", "ops")
        remote.seedFile("agents-ops-idx", "agents-ops", "index.html", docsBytes)

        fs.writeFile(root, "keep.txt", keepBytes)
        val docsLocal = "Agents/docs/ops/index.html"
        fs.createDirectory(root, PathUtil.parentOf(docsLocal))
        fs.writeFile(root, docsLocal, docsBytes)

        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep-f",
                parentRemoteId = "cloud-root",
                relativePath = "keep.txt",
                isFolder = false,
                sizeBytes = keepBytes.size.toLong(),
                contentHash = ContentHashUtil.sha256Hex(keepBytes)
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "del-f",
                parentRemoteId = "cloud-root",
                relativePath = "at-sync-threeway-delete-test",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-root",
                parentRemoteId = "cloud-root",
                relativePath = "Agents",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-docs",
                parentRemoteId = "agents-root",
                relativePath = "Agents/docs",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-ops",
                parentRemoteId = "agents-docs",
                relativePath = "Agents/docs/ops",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "agents-ops-idx",
                parentRemoteId = "agents-ops",
                relativePath = docsLocal,
                isFolder = false,
                sizeBytes = docsBytes.size.toLong(),
                contentHash = ContentHashUtil.sha256Hex(docsBytes)
            )
        )

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(String(fs.readFile(root, "at-sync-threeway-download-test")))
            .isEqualTo("api-only-download-116")
        assertThat(remote.get("del-f")).isNull()
        assertThat(store.getAll("m1").none { it.remoteId == "del-f" }).isTrue()
        // Must not wipe Agents/docs as false remote-Removed (1.0.115 queue-only protect gap).
        assertThat(fs.exists(root, docsLocal)).isTrue()
        assertThat(store.getAll("m1").any { it.remoteId == "agents-ops-idx" }).isTrue()
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
    }

    @Test
    fun agentsRoleQueuePaths_isUnderAgentsTree_coversDocsAndQueues() {
        assertThat(AgentsRoleQueuePaths.isUnderAgentsTree("Agents")).isTrue()
        assertThat(
            AgentsRoleQueuePaths.isUnderAgentsTree("Protolink.ru/Agents/docs/ops/index.html")
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.isUnderAgentsTree(
                "Protolink.ru/Agents/roles/frontend-developer/todo/P0/index.html"
            )
        ).isTrue()
        assertThat(AgentsRoleQueuePaths.isUnderAgentsTree("notes/keep.txt")).isFalse()
        assertThat(AgentsRoleQueuePaths.isUnderAgentsTree("films/AgentsBackup")).isFalse()
        // Queue matcher still false for docs (gap that 1.0.115 left open).
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/docs/ops/index.html"
            )
        ).isFalse()
    }

    @Test
    fun agentsRoleQueuePaths_protectsMailboxQueues_inboxOutbox() {
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/inbox"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/inbox/todo/ask-hello/index.html"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/inbox/done/P1-old-ask/index.html"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/outbox/todo/Q1-ask"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/outbox/done/Q1-closed"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/docs/ops/index.html"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/output/index.html"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor(
                "Protolink.ru/Agents/inbox/progress"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.matchesOpenQueueOrAgentsRolesAncestor("films/inbox/todo")
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/inbox/todo/ghost-ask"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/outbox/done/ghost-ask"
            )
        ).isTrue()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/inbox/todo"
            )
        ).isFalse()
        assertThat(
            AgentsRoleQueuePaths.shouldSkipFolderMaterialize(
                "Protolink.ru/Agents/outbox/done"
            )
        ).isFalse()
    }

    @Test
    fun forcePull_doesNotPruneAgentsRoleOpenQueueTaskPage() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        val planted =
            "Protolink.ru/Agents/roles/frontend-developer/todo/P1-20261118-planted/index.html"
        fs.writeFile(root, planted, "planted-agents-queue".toByteArray())
        fs.writeFile(root, "ghost-root.txt", "junk".toByteArray())

        SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(fs.exists(root, planted)).isTrue()
        assertThat(String(fs.readFile(root, planted))).isEqualTo("planted-agents-queue")
        assertThat(fs.exists(root, "ghost-root.txt")).isFalse()
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
    }

    @Test
    fun forcePull_doesNotPruneAgentsMailboxQueueAskPage() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        val inboxAsk =
            "Protolink.ru/Agents/inbox/todo/ask-planted-mailbox/index.html"
        val outboxDone =
            "Protolink.ru/Agents/outbox/done/Q1-closed/index.html"
        fs.writeFile(root, inboxAsk, "planted-mailbox-ask".toByteArray())
        fs.writeFile(root, outboxDone, "planted-outbox-done".toByteArray())
        fs.writeFile(root, "ghost-root.txt", "junk".toByteArray())

        SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(fs.exists(root, inboxAsk)).isTrue()
        assertThat(String(fs.readFile(root, inboxAsk))).isEqualTo("planted-mailbox-ask")
        assertThat(fs.exists(root, outboxDone)).isTrue()
        assertThat(String(fs.readFile(root, outboxDone))).isEqualTo("planted-outbox-done")
        assertThat(fs.exists(root, "ghost-root.txt")).isFalse()
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
    }

    @Test
    fun forcePull_skipsAgentsOpenQueueFolderOnlyMaterialize() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        remote.seedFolder("pl-ru", "cloud-root", "Protolink.ru")
        remote.seedFolder("agents", "pl-ru", "Agents")
        remote.seedFolder("roles", "agents", "roles")
        remote.seedFolder("role", "roles", "frontend-developer")
        remote.seedFolder("todo", "role", "todo")
        remote.seedFolder("shell", "todo", "ghost-shell")

        SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(
            fs.exists(root, "Protolink.ru/Agents/roles/frontend-developer/todo/ghost-shell")
        ).isFalse()
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
    }

    /**
     * Live 1.0.76 FORCE_MID_INVENTORY: after wave≈16, Force kept BFS-listing under every
     * Agents open-queue task shell (SkipMkdir only skipped mkdir). Must not listChildren the
     * shell or its nested evidence tree — Force still completes AC fill.
     */
    @Test
    fun forcePull_doesNotListUnderAgentsOpenQueueTaskShells() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films-f", "cloud-root", "films")
        inner.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        inner.seedFolder("pl-ru", "cloud-root", "Protolink.ru")
        inner.seedFolder("agents", "pl-ru", "Agents")
        inner.seedFolder("roles", "agents", "roles")
        inner.seedFolder("role", "roles", "frontend-developer")
        inner.seedFolder("todo", "role", "todo")
        inner.seedFolder("shell", "todo", "p1-deep-shell")
        // Nested under skip shell — must never be listed/downloaded.
        inner.seedFolder("nested", "shell", "evidence")
        inner.seedFile("blob", "nested", "dump.bin", ByteArray(64) { 1 })
        val listedParents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                listedParents.add(parentId)
                return inner.listChildrenPaged(parentId, includeValues)
            }

            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                listedParents.addAll(parentIds)
                return parentIds.flatMap { listChildrenPaged(it, includeValues) }
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isAtLeast(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(listedParents).doesNotContain("shell")
        assertThat(listedParents).doesNotContain("nested")
        assertThat(
            fs.exists(root, "Protolink.ru/Agents/roles/frontend-developer/todo/p1-deep-shell")
        ).isFalse()
        assertThat(
            fs.exists(
                root,
                "Protolink.ru/Agents/roles/frontend-developer/todo/p1-deep-shell/evidence/dump.bin"
            )
        ).isFalse()
    }

    /**
     * Live 1.0.77 FORCE_MID_INVENTORY: open-queue frontier-stop cleared wave16 stall, but Force
     * still BFS-listed every Agents role done task shell (+ evidence) for ~15m. Must not
     * listChildren done shells; AC fill still completes; local done page prune-protected.
     */
    @Test
    fun forcePull_doesNotListUnderAgentsDoneTaskShells() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films-f", "cloud-root", "films")
        inner.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        inner.seedFolder("pl-ru", "cloud-root", "Protolink.ru")
        inner.seedFolder("agents", "pl-ru", "Agents")
        inner.seedFolder("roles", "agents", "roles")
        inner.seedFolder("role", "roles", "frontend-developer")
        inner.seedFolder("done", "role", "done")
        inner.seedFolder("shell", "done", "p1-done-shell")
        inner.seedFolder("nested", "shell", "evidence")
        inner.seedFile("blob", "nested", "dump.bin", ByteArray(64) { 1 })
        inner.seedFile("page", "shell", "index.html", "done-remote".toByteArray())
        val planted =
            "Protolink.ru/Agents/roles/frontend-developer/done/p1-done-shell/index.html"
        fs.writeFile(root, planted, "done-local".toByteArray())
        val listedParents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                listedParents.add(parentId)
                return inner.listChildrenPaged(parentId, includeValues)
            }

            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                listedParents.addAll(parentIds)
                return parentIds.flatMap { listChildrenPaged(it, includeValues) }
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isAtLeast(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(listedParents).doesNotContain("shell")
        assertThat(listedParents).doesNotContain("nested")
        assertThat(fs.exists(root, planted)).isTrue()
        assertThat(String(fs.readFile(root, planted))).isEqualTo("done-local")
        assertThat(
            fs.exists(
                root,
                "Protolink.ru/Agents/roles/frontend-developer/done/p1-done-shell/evidence/dump.bin"
            )
        ).isFalse()
    }

    @Test
    fun forcePull_emptyRemoteBytes_writesAndContinues() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("empty-f", "films-f", "empty.txt", ByteArray(0))
        remote.seedFile("ok-f", "films-f", "ok.txt", "ok".toByteArray())

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(fs.readFile(root, "films/empty.txt")).isEmpty()
        assertThat(String(fs.readFile(root, "films/ok.txt"))).isEqualTo("ok")
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
        remote.seedFile("ok-f", "films-f", "ok.txt", "ok".toByteArray())

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
        assertThat(String(fs.readFile(root, "films/ok.txt"))).isEqualTo("ok")
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
        // Non-AC blobs are structure-registered only; Notes leaf pages still getFile.
        inner.seedFile("leaf", "b", "index.html", "ok".toByteArray())

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
        assertThat(String(fs.readFile(root, "a/b/index.html"))).isEqualTo("ok")
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
        // Remote leaf under soft-fail parent was never listed → not downloaded.
        assertThat(fs.exists(root, "bad/gone.txt")).isFalse()
    }

    /**
     * Soft-fail empty nested inventory must not prune local children (inbox/output wipe class).
     * Win parity: ForcePull_SoftFailEmptyInventory_DoesNotPruneLocalChildren.
     */
    @Test
    fun forcePull_softFailEmptyInventory_doesNotPruneLocalChildren() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("agents", "cloud-root", "Agents")
        inner.seedFolder("films", "cloud-root", "films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())

        fs.createDirectory(root, "Agents")
        fs.createDirectory(root, "Agents/inbox")
        fs.createDirectory(root, "Agents/inbox/ask-1")
        fs.writeFile(root, "Agents/inbox/ask-1/index.html", "planted-inbox".toByteArray())
        fs.createDirectory(root, "Agents/output")
        fs.writeFile(root, "Agents/output/note.txt", "planted-output".toByteArray())
        fs.writeFile(root, "ghost-root.txt", "local-only-ghost".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentId == "agents") {
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

        val engine = SyncEngine(store, fs, remote)
        val count = engine.forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "Agents/inbox/ask-1/index.html"))).isEqualTo("planted-inbox")
        assertThat(String(fs.readFile(root, "Agents/output/note.txt"))).isEqualTo("planted-output")
        assertThat(fs.exists(root, "ghost-root.txt")).isFalse()
        assertThat(
            engine.isUnderForceInventoryUnknown(
                "Agents/inbox/x",
                setOf("Agents")
            )
        ).isTrue()
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
     * Warm values-light must null listing UpdateTime so structure-only "newer" times do not
     * force getFile (Win 1.0.77 quiet-meta hang class). Unchanged size+hash → skip download.
     */
    @Test
    fun reconcile_warmMeta_nullsListingUpdateTime_skipsGetFile() = runTest {
        val (store, fs, inner) = harness()
        val body = "stable-note-body-xxxxxxxx"
        val hash = ContentHashUtil.sha256Hex(body.toByteArray())
        val stamped = Instant.parse("2026-11-16T12:00:00Z")
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", body.toByteArray())
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "films",
                parentRemoteId = "cloud-root",
                relativePath = "Films",
                isFolder = true,
                sizeBytes = 0,
                contentHash = "",
                remoteUpdateTime = stamped
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "idx",
                parentRemoteId = "films",
                relativePath = "Films/index.html",
                isFolder = false,
                sizeBytes = body.length.toLong(),
                contentHash = hash,
                remoteUpdateTime = stamped
            )
        )
        fs.writeFile(root, "Films/index.html", body.toByteArray())

        val downloads = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> =
                inner.listChildrenPaged(parentId, includeValues).map {
                    // Structure-only listing advertises a newer UpdateTime — warm walk must ignore it.
                    if (!includeValues) {
                        it.copy(
                            name = if (it.isFolder) "folder-${it.id}" else "file-${it.id}",
                            updateTime = Instant.parse("2026-11-16T18:00:00Z")
                        )
                    } else {
                        it
                    }
                }

            override suspend fun downloadFile(entityId: String): ByteArray? {
                downloads.incrementAndGet()
                return inner.downloadFile(entityId)
            }
        }

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo(body)
        assertThat(downloads.get()).isEqualTo(0)
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
    }

    /**
     * Ids IncludeValues soft-fail must not silently drop Force folders — Win folder-{id} policy.
     * Sibling named AC still fills.
     */
    @Test
    fun forcePull_idsNamesSoftFail_keepsFolderSyntheticAndFillsSibling() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("orphan", "cloud-root", "OrphanBranch")
        inner.seedFile("orphan-idx", "orphan", "index.html", "o".toByteArray())

        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun getEntitiesByIds(
                ids: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                // Soft-fail orphan* only — siblings in the same Ids batch must still resolve.
                return inner.getEntitiesByIds(ids, includeValues)
                    .filter { it.id != "orphan" && it.id != "orphan-idx" }
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(fs.exists(root, "folder-orphan")).isTrue()
        assertThat(fs.exists(root, "OrphanBranch")).isFalse()
    }

    /**
     * Mid-pass FileNotFound during compare must soft-fail the item and still stamp LastSync.
     */
    @Test
    fun reconcile_missingLocalDuringCompare_softFailsAndContinues() = runTest {
        val (store, fs, inner) = harness()
        val body = "keep-me"
        val hash = ContentHashUtil.sha256Hex(body.toByteArray())
        val stamped = Instant.parse("2026-11-16T12:00:00Z")
        inner.seedFile("keep", "cloud-root", "keep.txt", body.toByteArray())
        inner.seedFile("gone", "cloud-root", "gone.txt", "x".toByteArray())
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep",
                parentRemoteId = "cloud-root",
                relativePath = "keep.txt",
                isFolder = false,
                sizeBytes = body.length.toLong(),
                contentHash = "",
                remoteUpdateTime = stamped
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "gone",
                parentRemoteId = "cloud-root",
                relativePath = "gone.txt",
                isFolder = false,
                sizeBytes = 1,
                contentHash = "",
                remoteUpdateTime = stamped
            )
        )
        fs.writeFile(root, "keep.txt", body.toByteArray())
        fs.writeFile(root, "gone.txt", "x".toByteArray())

        val vanishing = object : ru.protolink.communicator.sync.ports.LocalFileSystem by fs {
            override fun readFile(rootPath: String, relativePath: String): ByteArray {
                if (relativePath == "gone.txt") error("Missing gone.txt")
                return fs.readFile(rootPath, relativePath)
            }
        }

        SyncEngine(store, vanishing, inner).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "keep.txt"))).isEqualTo(body)
        assertThat(store.getLastSyncUtc("m1")).isNotNull()
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
            inner.seedFile("file$i", "f$i", "index.html", "x$i".toByteArray())
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
        assertThat(fs.exists(root, "Folder0/index.html")).isTrue()
        assertThat(fs.exists(root, "Folder59/index.html")).isTrue()
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
            inner.seedFile("n$i", "f$i", "index.html", "x".toByteArray())
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
        assertThat(fs.exists(root, "Folder0/index.html")).isTrue()
        assertThat(fs.exists(root, "Folder${folderCount - 1}/index.html")).isTrue()
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
        inner.seedFile("note", "agents", "index.html", "n".toByteArray())

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
        assertThat(String(fs.readFile(root, "Agents/index.html"))).isEqualTo("n")
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
        inner.seedFile("deep", "d2", "index.html", "x".toByteArray())

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
        assertThat(String(fs.readFile(root, "Deep/Nested/index.html"))).isEqualTo("x")
    }

    /**
     * Live 1.0.75 FORCE_MID_INVENTORY: early prune called full-tree snapshot (SAF content-hash)
     * even when root ghosts were already absent — stalled after root kids before wave/getFile.
     * Pre-matched local tree must not snapshot during Force.
     */
    @Test
    fun forcePull_noGhosts_doesNotSnapshotWholeTree() = runTest {
        val (store, innerFs, remote) = harness()
        var snapshots = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                snapshots++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
            }
        }
        remote.seedFolder("films", "cloud-root", "Films")
        remote.seedFile("idx", "films", "index.html", "movie".toByteArray())
        remote.seedFolder("atv", "cloud-root", "AgentTeamVerify")
        remote.seedFile("t1", "atv", "a.txt", "a".toByteArray())
        // Wide root peers (live ~380) already present locally — no ghosts.
        repeat(60) { i ->
            remote.seedFile("root$i", "cloud-root", "shot-$i.png", "img$i".toByteArray())
            innerFs.writeFile(root, "shot-$i.png", "img$i".toByteArray())
        }
        innerFs.createDirectory(root, "Films")
        innerFs.writeFile(root, "Films/index.html", "movie".toByteArray())
        innerFs.createDirectory(root, "AgentTeamVerify")
        innerFs.writeFile(root, "AgentTeamVerify/a.txt", "a".toByteArray())
        // Large already-synced tree that a hashed snapshot would thrash.
        repeat(80) { i ->
            innerFs.writeFile(root, "Agents/deep/file-$i.html", "x$i".toByteArray())
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(snapshots).isEqualTo(0)
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "AgentTeamVerify/a.txt"))).isEqualTo("a")
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
        inner.seedFile("deepf", "deep", "index.html", "x".toByteArray())
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

    /**
     * Live 1.0.58 FAIL: root-wave awaitAll downloaded all loose root files before prune —
     * confluence ghosts stayed for ~28m. Prune must run before any root-file getFile.
     */
    @Test
    fun forcePull_prunesRootGhostsBeforeRootFileDownloads() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        repeat(12) { i ->
            inner.seedFile("root$i", "cloud-root", "shot-$i.png", "img$i".toByteArray())
        }
        fs.createDirectory(root, "confluence")
        fs.writeFile(root, "confluence/page.html", "ghost".toByteArray())
        fs.createDirectory(root, "databases")

        val ghostGoneBeforeAcGetFile = java.util.concurrent.atomic.AtomicBoolean(false)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                if (entityId == "idx" && !ghostGoneBeforeAcGetFile.get()) {
                    ghostGoneBeforeAcGetFile.set(
                        !fs.exists(root, "confluence") && !fs.exists(root, "databases")
                    )
                }
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        // Root screenshots are register-only (not getFile); AC films still downloads.
        assertThat(count).isEqualTo(1)
        assertThat(ghostGoneBeforeAcGetFile.get()).isTrue()
        assertThat(fs.exists(root, "confluence")).isFalse()
        assertThat(fs.exists(root, "databases")).isFalse()
        assertThat(fs.exists(root, "shot-0.png")).isFalse()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
    }

    /**
     * Live 1.0.62 FAIL: unbounded root/deep blob getFile blocked Force-complete.
     * AC folder files download; mapping-root screenshots are register-only (no getFile).
     */
    @Test
    fun forcePull_acFolderFilesBeforeDeferredRootFiles() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("atv", "cloud-root", "AgentTeamVerify")
        inner.seedFile("t1", "atv", "a.txt", "a".toByteArray())
        repeat(20) { i ->
            inner.seedFile("root$i", "cloud-root", "shot-$i.png", "img$i".toByteArray())
        }

        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                order.add(entityId)
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(order).containsAtLeast("idx", "t1")
        assertThat(order.none { it.startsWith("root") }).isTrue()
        assertThat(fs.exists(root, "shot-0.png")).isFalse()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "AgentTeamVerify/a.txt"))).isEqualTo("a")
    }

    /**
     * Live 1.0.57 FAIL: full collectForceStructureEdges before any getFile — MatchAny 5xx
     * kept Force pre-getFile for 23m+. Progressive structure must download root-wave files
     * before listing deeper parents.
     */
    @Test
    fun forcePull_progressiveStructure_downloadsBeforeDeepList() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("d1", "cloud-root", "Deep")
        inner.seedFolder("d2", "d1", "Nested")
        inner.seedFile("deep", "d2", "index.html", "x".toByteArray())

        val deepListed = java.util.concurrent.atomic.AtomicBoolean(false)
        val filmsDownloadedBeforeDeepList = java.util.concurrent.atomic.AtomicBoolean(false)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenPaged(
                parentId: String,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentId == "d1" || parentId == "d2") deepListed.set(true)
                return inner.listChildrenPaged(parentId, includeValues)
            }

            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentIds.any { it == "d1" || it == "d2" }) deepListed.set(true)
                return inner.listChildrenOfParents(parentIds, includeValues)
            }

            override suspend fun downloadFile(entityId: String): ByteArray? {
                if (entityId == "idx" && !deepListed.get()) {
                    filmsDownloadedBeforeDeepList.set(true)
                }
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(filmsDownloadedBeforeDeepList.get()).isTrue()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "Deep/Nested/index.html"))).isEqualTo("x")
    }

    /**
     * MatchAny batch HTTP 504 must fall back after BATCH_MAX_ATTEMPTS (1) and still fill.
     * Same-priority peers (WideA/WideB) are batched together after AC folders.
     */
    @Test
    fun forcePull_matchAnyHttp504_fallsBackAndFills() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("wa", "cloud-root", "WideA")
        inner.seedFile("a1", "wa", "index.html", "a".toByteArray())
        inner.seedFolder("wb", "cloud-root", "WideB")
        inner.seedFile("b1", "wb", "index.html", "b".toByteArray())

        val batchAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun listChildrenOfParents(
                parentIds: List<String>,
                includeValues: Boolean
            ): List<ru.protolink.communicator.sync.model.RemoteEntry> {
                if (parentIds.size > 1) {
                    batchAttempts.incrementAndGet()
                    throw RuntimeException("HTTP 504 Gateway Time-out")
                }
                return inner.listChildrenOfParents(parentIds, includeValues)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(batchAttempts.get()).isAtMost(SyncInventoryFetch.BATCH_MAX_ATTEMPTS)
        assertThat(batchAttempts.get()).isGreaterThan(0)
        assertThat(String(fs.readFile(root, "WideA/index.html"))).isEqualTo("a")
        assertThat(String(fs.readFile(root, "WideB/index.html"))).isEqualTo("b")
    }

    /** AC folders (films/agentteamverify) materialize before lower-priority sibling trees. */
    @Test
    fun forcePull_priorityMaterializesFilmsBeforeWideSibling() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("wide", "cloud-root", "WideTree")
        inner.seedFile("w1", "wide", "index.html", "w".toByteArray())
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

    /** Live 1.0.62: non-AC blobs must not getFile — structure register only so Force can complete. */
    @Test
    fun forcePull_skipsNonAcBlobGetFile_stillCompletesAndPrunes() = runTest {
        val (store, fs, inner) = harness()
        inner.seedFolder("films", "cloud-root", "Films")
        inner.seedFile("idx", "films", "index.html", "movie".toByteArray())
        inner.seedFolder("agents", "cloud-root", "protolink.ru")
        inner.seedFolder("docs", "agents", "agents")
        inner.seedFile("blob", "docs", "dump.bin", "BIG".toByteArray())
        inner.seedFile("note", "docs", "index.html", "note".toByteArray())
        fs.createDirectory(root, "confluence")

        val downloaded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val remote = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                downloaded.add(entityId)
                return inner.downloadFile(entityId)
            }
        }

        val count = SyncEngine(store, fs, remote).forcePullMapping(mapping)

        assertThat(count).isEqualTo(2)
        assertThat(downloaded).containsAtLeast("idx", "note")
        assertThat(downloaded).doesNotContain("blob")
        assertThat(fs.exists(root, "protolink.ru/agents/dump.bin")).isFalse()
        assertThat(String(fs.readFile(root, "Films/index.html"))).isEqualTo("movie")
        assertThat(String(fs.readFile(root, "protolink.ru/agents/index.html"))).isEqualTo("note")
        assertThat(fs.exists(root, "confluence")).isFalse()
        assertThat(fs.exists(root, "protolink.ru/agents")).isTrue()
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

    /** D-PUSH-FOLDERS: timer push must upgrade folders-only meta to full reconcile (not skip). */
    @Test
    fun pushLocal_foldersOnlyMeta_upgradesToFullReconcile() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "push-heal".toByteArray())

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

        SyncEngine(store, fs, remote).pushLocalChanges(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("push-heal")
        assertThat(store.getAll("m1").any { !it.isFolder && it.remoteId == "films-i" }).isTrue()
    }

    /**
     * Win ApplyCompare: per-meta Exists+Length only — no full-tree snapshot during compare.
     * Live 1.0.99 residual: Compare snapshot starved ApplyRemote (DOWNLOAD_API_ONLY_NOT_ON_SAF).
     */
    @Test
    fun applyCompare_doesNotFullSnapshot_usesFileLength() = runTest {
        val (store, innerFs, remote) = harness()
        var snapshots = 0
        var lengthProbes = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                snapshots++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
            }

            override fun fileLengthBytes(rootPath: String, relativePath: String): Long? {
                lengthProbes++
                return innerFs.fileLengthBytes(rootPath, relativePath)
            }
        }
        remote.seedFile("f1", "cloud-root", "keep.txt", "same".toByteArray())
        innerFs.writeFile(root, "keep.txt", "same".toByteArray())
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "f1",
                parentRemoteId = "cloud-root",
                relativePath = "keep.txt",
                isFolder = false,
                sizeBytes = 4,
                contentHash = ContentHashUtil.sha256Hex("same".toByteArray()),
                remoteUpdateTime = java.time.Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        remote.seedFolder("dl-f", "cloud-root", "at-sync-threeway-download-test")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(fs.exists(root, "at-sync-threeway-download-test")).isTrue()
        assertThat(lengthProbes).isAtLeast(1)
        // Local phase still snapshots once; Compare must not add another full walk for meta files.
        assertThat(snapshots).isAtMost(4)
    }

    /**
     * Win ApplyLocal reconcile: SnapshotFs(computeContentHashes: false).
     * Live ≥1.0.101 residual: SAF always hashed whole MyNotesFolder before Api → sticky Uploading.
     */
    @Test
    fun applyLocal_reconcile_snapshotSkipsContentHashes() = runTest {
        val (store, innerFs, remote) = harness()
        var hashedSnaps = 0
        var sizeOnlySnaps = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                if (computeContentHashes) hashedSnaps++ else sizeOnlySnaps++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
            }
        }
        remote.seedFolder("keep-f", "cloud-root", "keep")
        innerFs.createDirectory(root, "keep")
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "keep-f",
                parentRemoteId = "cloud-root",
                relativePath = "keep",
                isFolder = true,
                sizeBytes = 0
            )
        )
        // Local-new folder must still upload (path presence — no hash needed).
        innerFs.createDirectory(root, "at-sync-threeway-upload-test")

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(remote.childrenOf("cloud-root").any {
            it.name.equals("at-sync-threeway-upload-test", ignoreCase = true)
        }).isTrue()
        assertThat(sizeOnlySnaps).isAtLeast(1)
        // Reconcile ApplyLocal must not request hashed snapshot.
        assertThat(hashedSnaps).isEqualTo(0)
    }

    /**
     * Win ApplyRemote.Updated: FileInfo.Length one path — not full snapshot per item.
     * Live residual: per-Updated SafLocalFileSystem.snapshot re-starved large trees.
     */
    @Test
    fun applyRemote_updated_usesFileLengthNotFullSnapshot() = runTest {
        val (store, innerFs, remote) = harness()
        var snapshots = 0
        var lengthProbes = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                snapshots++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
            }

            override fun fileLengthBytes(rootPath: String, relativePath: String): Long? {
                lengthProbes++
                return innerFs.fileLengthBytes(rootPath, relativePath)
            }
        }
        val oldTime = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val newTime = java.time.Instant.parse("2026-06-01T00:00:00Z")
        remote.seedFile("f1", "cloud-root", "note.txt", "server-new".toByteArray())
        remote.get("f1")!!.updateTime = newTime
        // Same size as local so Compare size path may miss; Updated safety net still runs.
        innerFs.writeFile(root, "note.txt", "local-old!".toByteArray())
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "f1",
                parentRemoteId = "cloud-root",
                relativePath = "note.txt",
                isFolder = false,
                sizeBytes = 10,
                contentHash = ContentHashUtil.sha256Hex("local-old!".toByteArray()),
                remoteUpdateTime = oldTime
            )
        )

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        // Updated safety net must probe length, not N full snaps per item.
        assertThat(lengthProbes).isAtLeast(1)
        assertThat(snapshots).isAtMost(4)
    }

    /**
     * Force upload: size-only snapshot then hash at readFile — first Api must not wait on
     * whole-tree SAF SHA-256 (FORCE_UPLOAD_STUCK_NO_PROGRESS on e93e0fa5).
     */
    @Test
    fun forcePush_snapshotSkipsContentHashes() = runTest {
        val (store, innerFs, remote) = harness()
        var hashedSnaps = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                if (computeContentHashes) hashedSnaps++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
            }
        }
        innerFs.writeFile(root, "upload-me.txt", "payload".toByteArray())

        val count = SyncEngine(store, fs, remote).forcePushMapping(mapping)

        assertThat(count).isEqualTo(1)
        assertThat(hashedSnaps).isEqualTo(0)
        assertThat(remote.childrenOf("cloud-root").any {
            it.name.equals("upload-me.txt", ignoreCase = true)
        }).isTrue()
    }

    /** Bootstrap must not call full-tree snapshot once per remote item (O(n²) on polluted roots). */
    @Test
    fun bootstrap_doesNotSnapshotWholeTreePerRemoteItem() = runTest {
        val (store, innerFs, remote) = harness()
        var snapshots = 0
        val fs = object : ru.protolink.communicator.sync.ports.LocalFileSystem by innerFs {
            override fun snapshot(
                rootPath: String,
                computeContentHashes: Boolean,
                skipRelativePath: ((String) -> Boolean)?
            ): List<FsEntry> {
                snapshots++
                return innerFs.snapshot(rootPath, computeContentHashes, skipRelativePath)
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
        // Win ApplyLocal: sparse/non-empty remote still uploads local-only paths (no prune fork).
        assertThat(fs.exists(root, "evidence-0.png")).isTrue()
        assertThat(remote.childrenOf("cloud-root").any { it.name.equals("evidence-0.png", true) }).isTrue()
    }

    /**
     * Populated local tree + sparse non-empty remote: children survive full Sync and upload
     * (Win AddEntity/AddFile parity — ADR sync-simple-win-reference). Former
     * reconcile_prunesCloudAbsentLocalWhenRemoteNonEmpty asserted Android-only delete-ghost.
     * Tip 1.0.115: Agents mailbox queues use AutoPush exclude on Sync ApplyLocal — local
     * Agents survives but is not uploaded (sticky Syncing / Agents churn class).
     */
    @Test
    fun reconcile_sparseRemote_uploadsLocalOnlyChildren() = runTest {
        val (store, fs, remote) = harness()
        remote.seedFolder("films-f", "cloud-root", "films")
        remote.seedFile("films-i", "films-f", "index.html", "movie".toByteArray())
        fs.writeFile(root, "tor browser/readme.txt", "junk".toByteArray())
        fs.createDirectory(root, "confluence")
        fs.writeFile(root, "Agents/inbox/todo/note.txt", "keep".toByteArray())

        SyncEngine(store, fs, remote).reconcileAll(listOf(mapping))

        assertThat(String(fs.readFile(root, "films/index.html"))).isEqualTo("movie")
        assertThat(fs.exists(root, "tor browser")).isTrue()
        assertThat(fs.exists(root, "tor browser/readme.txt")).isTrue()
        assertThat(fs.exists(root, "confluence")).isTrue()
        assertThat(fs.exists(root, "Agents/inbox/todo/note.txt")).isTrue()
        assertThat(remote.childrenOf("cloud-root").any { it.name.equals("tor browser", true) }).isTrue()
        assertThat(remote.childrenOf("cloud-root").any { it.name.equals("confluence", true) }).isTrue()
        assertThat(remote.childrenOf("cloud-root").none { it.name.equals("Agents", true) }).isTrue()
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

    /**
     * Live 1.0.64 FORCE_SYNC_LOCK_TIMEOUT: compare-phase item loops must honour Job cancel
     * so SyncFlight Force can unlock ≪120s (Win ThrowIfCancellationRequested parity).
     */
    @Test
    fun reconcile_comparePhase_abortsPromptlyOnCancel() = runTest {
        val (store, fs, inner) = harness()
        // Empty meta hash + size mismatch so compare must getFile (warm null UpdateTime skips otherwise).
        repeat(40) { i ->
            val id = "f$i"
            val name = "n$i.txt"
            val bytes = "body-$i".toByteArray()
            inner.seedFile(id, "cloud-root", name, bytes)
            fs.writeFile(root, name, bytes)
            store.upsert(
                SyncItemMeta(
                    mappingId = mapping.id,
                    remoteId = id,
                    parentRemoteId = "cloud-root",
                    relativePath = name,
                    isFolder = false,
                    sizeBytes = 1,
                    contentHash = "",
                    remoteUpdateTime = Instant.EPOCH
                )
            )
        }
        var downloads = 0
        val delaying = object : ru.protolink.communicator.sync.ports.RemoteCloud by inner {
            override suspend fun downloadFile(entityId: String): ByteArray? {
                downloads++
                kotlinx.coroutines.delay(50)
                return inner.downloadFile(entityId)
            }
        }
        val job = launch {
            SyncEngine(store, fs, delaying).reconcileAll(listOf(mapping))
        }
        kotlinx.coroutines.delay(120)
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
        // Must not have finished all 40 delayed downloads after cancel.
        assertThat(downloads).isLessThan(40)
    }
}
