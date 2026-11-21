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
class SafDocumentSizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun lengthBytes_prefersListedSize_withoutOpeningStream() {
        // Win FileInfo.Length parity: size-only snap must not open content.
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Afake")
        val len = SafDocumentSize.lengthBytes(
            RuntimeEnvironment.getApplication(),
            uri,
            primaryFile = null,
            listedSize = 42L
        )
        assertThat(len).isEqualTo(42L)
    }

    @Test
    fun lengthBytes_prefersPrimaryFileLength() {
        val file = tmp.newFile("note.html").also { it.writeText("hello-world") }
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Anote.html")
        val len = SafDocumentSize.lengthBytes(
            RuntimeEnvironment.getApplication(),
            uri,
            primaryFile = file,
            listedSize = null
        )
        assertThat(len).isEqualTo(file.length())
        assertThat(len).isEqualTo(11L)
    }

    @Test
    fun lengthBytes_unknownWithoutStreamFallback_returnsZero() {
        // Must not readBytes an unreadable URI — Force starve class on large trees.
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Amissing")
        val len = SafDocumentSize.lengthBytes(
            RuntimeEnvironment.getApplication(),
            uri,
            primaryFile = null,
            listedSize = null
        )
        assertThat(len).isEqualTo(0L)
    }
}
