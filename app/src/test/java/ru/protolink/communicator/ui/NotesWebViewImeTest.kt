package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotesWebViewImeTest {

    @Test
    fun focusEditorJs_targetsEditorAndPlacesCaret() {
        val js = NotesWebViewIme.FOCUS_EDITOR_JS
        assertThat(js).contains("getElementById('editor')")
        assertThat(js).contains("e.focus()")
        assertThat(js).contains("createRange()")
        assertThat(js).contains("addRange")
    }
}
