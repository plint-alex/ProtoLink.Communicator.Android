package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerAttachMenuStringsTest {

    @Test
    fun en_labels_match_ux_table() {
        assertThat(MessengerAttachMenuStrings.camera("en")).isEqualTo("Camera")
        assertThat(MessengerAttachMenuStrings.gallery("en-US")).isEqualTo("Gallery")
        assertThat(MessengerAttachMenuStrings.file("en")).isEqualTo("File…")
        assertThat(MessengerAttachMenuStrings.chooseFromCloud(null))
            .isEqualTo("Choose from Cloud…")
    }

    @Test
    fun ru_labels_match_ux_table() {
        assertThat(MessengerAttachMenuStrings.camera("ru")).isEqualTo("Камера")
        assertThat(MessengerAttachMenuStrings.gallery("ru-RU")).isEqualTo("Галерея")
        assertThat(MessengerAttachMenuStrings.file("ru")).isEqualTo("Файл…")
        assertThat(MessengerAttachMenuStrings.chooseFromCloud("ru"))
            .isEqualTo("Выбрать из облака…")
    }

    @Test
    fun orderedLabels_android_cycle2_order() {
        assertThat(MessengerAttachMenuStrings.orderedLabels("en")).containsExactly(
            "Camera",
            "Gallery",
            "File…",
            "Choose from Cloud…"
        ).inOrder()
        assertThat(MessengerAttachMenuStrings.orderedLabels("ru")).containsExactly(
            "Камера",
            "Галерея",
            "Файл…",
            "Выбрать из облака…"
        ).inOrder()
    }
}
