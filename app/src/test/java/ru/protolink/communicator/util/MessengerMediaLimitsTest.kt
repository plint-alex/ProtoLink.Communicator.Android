package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerMediaLimitsTest {
    @Test
    fun isOversize_atLimitAllowed() {
        assertThat(MessengerMediaLimits.isOversize(MessengerMediaLimits.MaxAttachmentBytes)).isFalse()
        assertThat(MessengerMediaLimits.isOversize(MessengerMediaLimits.MaxAttachmentBytes + 1)).isTrue()
    }

    @Test
    fun oversizeMessage_matchesM11() {
        assertThat(MessengerMediaLimits.OversizeMessageEn).isEqualTo("Files up to 100 MB can be sent")
    }

    @Test
    fun formatAttachmentValue_lowercaseGuid() {
        assertThat(MessengerMediaLimits.formatAttachmentValue("3F2B1C44-9A0E-4F2B-8B1E-7A0C5D2E9F10"))
            .isEqualTo("attachment:3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10")
    }

    @Test
    fun cameraPermissionCopy_matchesUx() {
        assertThat(MessengerMediaLimits.CameraPermissionDeniedEn)
            .isEqualTo("Camera access is needed to take a photo")
    }
}
