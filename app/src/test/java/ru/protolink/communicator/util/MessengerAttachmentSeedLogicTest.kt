package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerAttachmentSeedLogicTest {
    @Test
    fun pdfSeed_startsAsFileRowImmediately() {
        val seed = MessengerAttachmentSeed(
            fileName = "waveb-messenger-test.pdf",
            mimeType = "application/pdf",
            byteLength = 2048L
        )
        assertThat(MessengerAttachmentSeedLogic.initialKind(seed))
            .isEqualTo(MessengerAttachmentVisualKind.File)
        assertThat(MessengerAttachmentSeedLogic.displayFileName(seed))
            .isEqualTo("waveb-messenger-test.pdf")
    }

    @Test
    fun imageSeed_staysLoadingUntilProbe() {
        val seed = MessengerAttachmentSeed(
            fileName = "shot.png",
            mimeType = "image/png",
            byteLength = 100L
        )
        assertThat(MessengerAttachmentSeedLogic.initialKind(seed))
            .isEqualTo(MessengerAttachmentVisualKind.Loading)
    }

    @Test
    fun nullSeed_isLoading() {
        assertThat(MessengerAttachmentSeedLogic.initialKind(null))
            .isEqualTo(MessengerAttachmentVisualKind.Loading)
        assertThat(MessengerAttachmentSeedLogic.displayFileName(null)).isEqualTo("…")
    }
}
