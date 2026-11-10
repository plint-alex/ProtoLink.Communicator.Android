package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UX-N-A4 — RU/EN format-bar contentDescriptions. */
class NotesFormatBarStringsTest {
    @Test
    fun bold_enRu() {
        assertThat(NotesFormatBarStrings.bold("en-US")).isEqualTo("Bold")
        assertThat(NotesFormatBarStrings.bold("ru-RU")).isEqualTo("Жирный")
    }

    @Test
    fun insertLink_enRu() {
        assertThat(NotesFormatBarStrings.insertLink("en")).isEqualTo("Insert link")
        assertThat(NotesFormatBarStrings.insertLink("ru")).isEqualTo("Вставить ссылку")
    }

    @Test
    fun indentOutdent_enRu() {
        assertThat(NotesFormatBarStrings.indent("en")).isEqualTo("Increase indent")
        assertThat(NotesFormatBarStrings.outdent("ru")).isEqualTo("Уменьшить отступ")
    }
}
