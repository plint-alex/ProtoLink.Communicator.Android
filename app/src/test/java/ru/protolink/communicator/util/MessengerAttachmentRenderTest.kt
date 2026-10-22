package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerAttachmentRenderTest {
    @Test
    fun looksLikeImageMime_matchesContract() {
        assertThat(MessengerAttachmentRender.looksLikeImageMime("image/png")).isTrue()
        assertThat(MessengerAttachmentRender.looksLikeImageMime("image/jpeg")).isTrue()
        assertThat(MessengerAttachmentRender.looksLikeImageMime("application/zip")).isFalse()
        assertThat(MessengerAttachmentRender.looksLikeImageMime("application/octet-stream")).isFalse()
        assertThat(MessengerAttachmentRender.looksLikeImageMime(null)).isFalse()
    }

    @Test
    fun initialKind_imageMime_isLoadingUntilProbe() {
        assertThat(MessengerAttachmentRender.initialKind("image/png", forceFileRow = false))
            .isEqualTo(MessengerAttachmentVisualKind.Loading)
        assertThat(MessengerAttachmentRender.initialKind("image/png", forceFileRow = true))
            .isEqualTo(MessengerAttachmentVisualKind.File)
        assertThat(MessengerAttachmentRender.initialKind("application/zip", forceFileRow = false))
            .isEqualTo(MessengerAttachmentVisualKind.File)
    }

    @Test
    fun afterImageProbe_mislabelledPng_degradesToFile() {
        // AT-M20
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = false,
            httpStatus = 200,
            contentType = "application/zip",
            decodedAsImage = false
        )
        assertThat(kind).isEqualTo(MessengerAttachmentVisualKind.File)
    }

    @Test
    fun afterImageProbe_successImage_isImage() {
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = false,
            httpStatus = 200,
            contentType = "image/png",
            decodedAsImage = true
        )
        assertThat(kind).isEqualTo(MessengerAttachmentVisualKind.Image)
    }

    @Test
    fun afterImageProbe_404_isTombstoneRemoved() {
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = false,
            httpStatus = 404,
            contentType = null,
            decodedAsImage = false
        )
        assertThat(kind).isEqualTo(MessengerAttachmentVisualKind.TombstoneRemoved)
        assertThat(MessengerAttachmentRender.TOMBSTONE_REMOVED_EN).isEqualTo("File removed")
    }

    @Test
    fun afterImageProbe_403_isTombstoneNoAccess() {
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = false,
            httpStatus = 403,
            contentType = null,
            decodedAsImage = false
        )
        assertThat(kind).isEqualTo(MessengerAttachmentVisualKind.TombstoneNoAccess)
        assertThat(MessengerAttachmentRender.TOMBSTONE_NO_ACCESS_EN)
            .isEqualTo("You don't have access to this item")
    }

    @Test
    fun afterImageProbe_sendAsFile_forcesFileEvenOnImageSuccess() {
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = true,
            httpStatus = 200,
            contentType = "image/png",
            decodedAsImage = true
        )
        assertThat(kind).isEqualTo(MessengerAttachmentVisualKind.File)
    }

    @Test
    fun splitBatches_twelve_yieldsTenPlusTwo() {
        val items = (1..12).toList()
        val batches = MessengerAttachmentRender.splitBatches(items, MessengerMediaLimits.MaxAttachmentsPerMessage)
        assertThat(batches).hasSize(2)
        assertThat(batches[0]).hasSize(10)
        assertThat(batches[1]).hasSize(2)
        assertThat(batches[0]).isEqualTo((1..10).toList())
        assertThat(batches[1]).isEqualTo(listOf(11, 12))
    }

    @Test
    fun formatByteSize_readable() {
        assertThat(MessengerAttachmentRender.formatByteSize(512)).isEqualTo("512 B")
        assertThat(MessengerAttachmentRender.formatByteSize(1536)).isEqualTo("1.5 KB")
        assertThat(MessengerAttachmentRender.formatByteSize(12L * 1024 * 1024)).contains("MB")
    }
}
