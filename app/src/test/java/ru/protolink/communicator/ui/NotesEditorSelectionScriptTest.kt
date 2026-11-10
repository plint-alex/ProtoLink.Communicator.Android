package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Ensures shared notes-editor.js reports hasSelection for Pattern 2 (UX-N-A2). */
class NotesEditorSelectionScriptTest {
    @Test
    fun notesEditorJs_reportsHasSelectionWithDebounce() {
        val js = File("src/main/assets/notes-editor.js").readText(Charsets.UTF_8)
        assertThat(js).contains("hasSelection")
        assertThat(js).contains("!sel.isCollapsed")
        assertThat(js).contains(", 150)")
    }
}
