package ru.protolink.communicator.data

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPrimaryPathTest {

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
}
