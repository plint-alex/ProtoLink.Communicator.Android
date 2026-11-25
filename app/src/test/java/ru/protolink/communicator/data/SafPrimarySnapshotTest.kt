package ru.protolink.communicator.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Win SnapshotFs parity for primary File walk (live map Documents/MyNotesFolder).
 */
class SafPrimarySnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun snapshot_sizeOnly_seesAdbStyleFixture_andRollsFolderSize() {
        val root = tmp.newFolder("mynotesfolder")
        val nested = java.io.File(root, "notes/child").also { it.mkdirs() }
        java.io.File(nested, "index.html").writeText("hello")
        val fixture = java.io.File(root, "at-sync-threeway-upload-test-20261121").also { it.mkdirs() }
        java.io.File(fixture, "readme-test.txt").writeText("fixture")

        val snap = SafPrimarySnapshot.snapshot(root, computeContentHashes = false)

        assertThat(snap.any { it.relativePath == "at-sync-threeway-upload-test-20261121" && it.isFolder })
            .isTrue()
        val readme = snap.first { it.relativePath == "at-sync-threeway-upload-test-20261121/readme-test.txt" }
        assertThat(readme.isFolder).isFalse()
        assertThat(readme.sizeBytes).isEqualTo(7L)
        assertThat(readme.contentHash).isEmpty()
        val index = snap.first { it.relativePath == "notes/child/index.html" }
        assertThat(index.sizeBytes).isEqualTo(5L)
        val childFolder = snap.first { it.relativePath == "notes/child" && it.isFolder }
        assertThat(childFolder.sizeBytes).isEqualTo(5L)
    }

    @Test
    fun snapshot_computeHashes_fillsContentHash() {
        val root = tmp.newFolder("hash-root")
        java.io.File(root, "a.txt").writeText("abc")
        val snap = SafPrimarySnapshot.snapshot(root, computeContentHashes = true)
        val file = snap.first { it.relativePath == "a.txt" }
        assertThat(file.contentHash).isNotEmpty()
        assertThat(file.sizeBytes).isEqualTo(3L)
    }

    @Test
    fun snapshot_omitsDeletedFolder_soClassifierCanRemove() {
        // Win GetDirectories: gone path absent from snap → LocalChange.Removed → DeleteEntity.
        val root = tmp.newFolder("delete-root")
        val fixture = java.io.File(root, "at-sync-threeway-delete-test").also { it.mkdirs() }
        java.io.File(fixture, "readme-test.txt").writeText("threeway")
        assertThat(
            SafPrimarySnapshot.snapshot(root, computeContentHashes = false)
                .any { it.relativePath == "at-sync-threeway-delete-test" && it.isFolder }
        ).isTrue()

        fixture.deleteRecursively()
        val after = SafPrimarySnapshot.snapshot(root, computeContentHashes = false)
        assertThat(after.any { it.relativePath.startsWith("at-sync-threeway-delete-test") })
            .isFalse()
    }

    @Test
    fun snapshot_doesNotInventFolderStub_forUnlistableName() {
        // Regression: ghost folder stubs at former file/folder paths blocked Removed (1.0.111).
        val root = tmp.newFolder("ghost-root")
        val real = java.io.File(root, "keep").also { it.mkdirs() }
        java.io.File(real, "a.txt").writeText("x")
        // Simulate Os.listdir ghost: create then delete so walk only sees real kids.
        val ghost = java.io.File(root, "ghost-name")
        ghost.mkdirs()
        ghost.deleteRecursively()
        val snap = SafPrimarySnapshot.snapshot(root, computeContentHashes = false)
        assertThat(snap.any { it.relativePath == "ghost-name" }).isFalse()
        assertThat(snap.any { it.relativePath == "keep" && it.isFolder }).isTrue()
    }

    @Test
    fun snapshot_seesEmptyFolder_afterFileMkdirs_winCreateDirectoryParity() {
        // 1.0.112 Omit + DocumentsContract-only create: empty Api download missed File snap.
        // Tip 1.0.113: File.mkdirs in createDirectory → empty folder must be Directory in snap.
        val root = tmp.newFolder("download-root")
        val empty = java.io.File(root, "at-sync-threeway-download-test").also { it.mkdirs() }
        assertThat(empty.isDirectory).isTrue()
        val snap = SafPrimarySnapshot.snapshot(root, computeContentHashes = false)
        assertThat(snap.any { it.relativePath == "at-sync-threeway-download-test" && it.isFolder })
            .isTrue()
    }

    @Test
    fun snapshot_skipRelativePath_omitsAgentsQueues_keepsSibling() {
        // Win SnapshotFs(skipRelativePath: ShouldSkipLocalPush) — AutoPush hash budget.
        val root = tmp.newFolder("skip-root")
        java.io.File(root, "notes").also { it.mkdirs() }
        java.io.File(root, "notes/keep.txt").writeText("stable-keep")
        val agentsTask = java.io.File(
            root,
            "Protolink.ru/Agents/roles/frontend-developer/todo/P0-churn-task"
        ).also { it.mkdirs() }
        java.io.File(agentsTask, "index.html").writeText("agents-v1")

        val snap = SafPrimarySnapshot.snapshot(
            root,
            computeContentHashes = true,
            skipRelativePath = ru.protolink.communicator.sync.engine.AgentsRoleQueuePaths::shouldSkipLocalPush
        )
        assertThat(snap.any { !it.isFolder && it.relativePath == "notes/keep.txt" }).isTrue()
        assertThat(snap.any { it.relativePath.contains("P0-churn-task") }).isFalse()
        assertThat(snap.any { it.relativePath.contains("Agents", ignoreCase = true) }).isFalse()
    }

    @Test
    fun classifyPrimaryChild_omitsEmptyGhost_keepsRealEmptyDir() {
        val root = tmp.newFolder("classify-root")
        val realEmpty = java.io.File(root, "real-empty").also { it.mkdirs() }
        assertThat(SafPrimaryPath.classifyPrimaryChild(realEmpty))
            .isEqualTo(SafPrimaryPath.PrimaryChildKind.Directory)
        val ghost = java.io.File(root, "ghost-empty")
        assertThat(ghost.exists()).isFalse()
        assertThat(SafPrimaryPath.classifyPrimaryChild(ghost))
            .isEqualTo(SafPrimaryPath.PrimaryChildKind.Omit)
    }
}
