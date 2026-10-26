package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerImageDownscaleTest {
    @Test
    fun shouldApply_requiresEnabledImageAndNotCloudPick() {
        assertThat(MessengerImageDownscale.shouldApply(true, "image/jpeg", false)).isTrue()
        assertThat(MessengerImageDownscale.shouldApply(true, "IMAGE/PNG", false)).isTrue()
        assertThat(MessengerImageDownscale.shouldApply(false, "image/jpeg", false)).isFalse()
        assertThat(MessengerImageDownscale.shouldApply(true, "application/pdf", false)).isFalse()
        assertThat(MessengerImageDownscale.shouldApply(true, "image/jpeg", true)).isFalse()
        assertThat(MessengerImageDownscale.shouldApply(true, null, false)).isFalse()
    }

    @Test
    fun targetSize_noUpscaleWhenAlreadySmall() {
        assertThat(MessengerImageDownscale.targetSize(800, 600)).isEqualTo(800 to 600)
        assertThat(MessengerImageDownscale.targetSize(2048, 1536)).isEqualTo(2048 to 1536)
    }

    @Test
    fun targetSize_scalesLongEdgeTo2048() {
        val (w, h) = MessengerImageDownscale.targetSize(4032, 3024)
        assertThat(maxOf(w, h)).isEqualTo(2048)
        assertThat(w).isEqualTo(2048)
        assertThat(h).isEqualTo(1536)
    }

    @Test
    fun targetSize_portraitLongEdge() {
        val (w, h) = MessengerImageDownscale.targetSize(3024, 4032)
        assertThat(maxOf(w, h)).isEqualTo(2048)
        assertThat(h).isEqualTo(2048)
        assertThat(w).isEqualTo(1536)
    }

    @Test
    fun decodeInSampleSize_powersOfTwo() {
        assertThat(MessengerImageDownscale.decodeInSampleSize(800, 600)).isEqualTo(1)
        assertThat(MessengerImageDownscale.decodeInSampleSize(4032, 3024)).isEqualTo(1)
        assertThat(MessengerImageDownscale.decodeInSampleSize(8192, 6144)).isEqualTo(4)
    }

    @Test
    fun jpegFileName_replacesExtension() {
        assertThat(MessengerImageDownscale.jpegFileName("photo.HEIC")).isEqualTo("photo.jpg")
        assertThat(MessengerImageDownscale.jpegFileName("noext")).isEqualTo("noext.jpg")
    }

    @Test
    fun constants_matchM6Box() {
        assertThat(MessengerImageDownscale.MaxLongEdge).isEqualTo(2048)
        assertThat(MessengerImageDownscale.JpegQuality).isEqualTo(82)
    }

    @Test
    fun strings_ruEn() {
        assertThat(MessengerImageDownscaleStrings.toggleTitle("en"))
            .isEqualTo("Reduce photo size before send")
        assertThat(MessengerImageDownscaleStrings.toggleTitle("ru-RU"))
            .isEqualTo("Уменьшать фото перед отправкой")
        assertThat(MessengerImageDownscaleStrings.toggleHint("en")).contains("2048")
        assertThat(MessengerImageDownscaleStrings.toggleHint("ru")).contains("2048")
    }
}
