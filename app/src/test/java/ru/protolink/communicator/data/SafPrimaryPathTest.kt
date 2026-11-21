package ru.protolink.communicator.data

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPrimaryPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolve_primaryDocumentsTree_toEmulatedPath() {
        val tree = Uri.parse(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2FMyNotesFolder%2Fmynotesfolder"
        )
        // JVM/Windows File may prefix a drive letter; device uses absolute /storage/….
        fun norm(p: String?) = p?.replace('\\', '/')?.trimStart('D', 'd', ':')
        val root = SafPrimaryPath.tryResolve(tree)
        assertThat(norm(root?.absolutePath))
            .endsWith("/storage/emulated/0/Documents/MyNotesFolder/mynotesfolder")
        val child = SafPrimaryPath.tryResolve(tree, "at-sync-threeway-upload-test/readme-test.txt")
        assertThat(norm(child?.absolutePath)).endsWith(
            "/storage/emulated/0/Documents/MyNotesFolder/mynotesfolder/" +
                "at-sync-threeway-upload-test/readme-test.txt"
        )
    }

    @Test
    fun resolve_nonPrimary_returnsNull() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-EFGH%3ANotes")
        assertThat(SafPrimaryPath.tryResolve(tree)).isNull()
    }

    @Test
    fun listChildNames_seesNestedAdbStyleFixture() {
        // Win File.Exists parity for shell creates: merge must not depend on listFiles alone.
        val root = tmp.newFolder("mynotesfolder")
        val fixture = java.io.File(root, "at-sync-threeway-upload-test-20261121").also { it.mkdirs() }
        java.io.File(fixture, "readme-test.txt").writeText("hello")
        val names = SafPrimaryPath.listChildNames(root)
        assertThat(names.map { it.lowercase() }).contains("at-sync-threeway-upload-test-20261121")
        assertThat(SafPrimaryPath.listChildNames(fixture).map { it.lowercase() })
            .contains("readme-test.txt")
    }

    @Test
    fun flushListing_nonPrimary_isNoOp() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-EFGH%3ANotes")
        SafPrimaryPath.flushListing(RuntimeEnvironment.getApplication(), tree)
    }

    @Test
    fun flushListing_nestedWaitZero_returnsImmediately() {
        // Per-dir 1.5s await starved Force on MyNotesFolder — nested must not block.
        val tree = Uri.parse(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2FMyNotesFolder%2Fmynotesfolder"
        )
        val started = System.nanoTime()
        SafPrimaryPath.flushListing(
            RuntimeEnvironment.getApplication(),
            tree,
            relativePath = "at-sync-threeway-upload-test",
            waitMs = SafPrimaryPath.FLUSH_WAIT_NESTED_MS
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertThat(elapsedMs).isLessThan(500L)
    }
}
