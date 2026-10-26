package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotesEditorViewportTest {
    @Test
    fun captureScriptReadsScrollAndCaret() {
        val script = NotesEditorViewport.buildCaptureScript()
        assertThat(script).contains("window.scrollY")
        assertThat(script).contains("getElementById('editor')")
        assertThat(script).contains("caretStart")
        assertThat(script).contains("JSON.stringify")
    }

    @Test
    fun applyInnerHtmlScriptEmbedsHtmlAndRestoresViewport() {
        val state = NotesEditorViewport.State(420.5, 12, 18)
        val script = NotesEditorViewport.buildApplyInnerHtmlScript("<p>hello &amp; world</p>", state)
        assertThat(script).contains("editor.innerHTML=")
        assertThat(script).contains("hello &amp; world")
        assertThat(script).contains("420.5")
        assertThat(script).contains("caretStart=12")
        assertThat(script).contains("caretEnd=18")
        assertThat(script).contains("window.scrollTo(0,scrollY)")
        assertThat(script).contains("return true")
    }

    @Test
    fun applyInnerHtmlScriptEscapesQuotesAndNullCaret() {
        val script = NotesEditorViewport.buildApplyInnerHtmlScript(
            """<p onclick="x">a</p>""",
            viewport = null
        )
        assertThat(script).contains("caretStart=null")
        assertThat(script).contains("caretEnd=null")
        // Gson JSON string embeds escaped quotes — must not break the IIFE.
        assertThat(script).contains("onclick")
        assertThat(script).doesNotContain("editor.innerHTML=<p")
    }

    @Test
    fun restoreScriptUsesViewport() {
        val script = NotesEditorViewport.buildRestoreScript(
            NotesEditorViewport.State(88.0, 3, 3)
        )
        assertThat(script).contains("88.0")
        assertThat(script).contains("caretStart=3")
        assertThat(script).contains("window.scrollTo(0,scrollY)")
    }

    @Test
    fun parseCaptureResultFromDoubleEncodedJson() {
        val inner = """{"scrollY":120.5,"caretStart":10,"caretEnd":15}"""
        val raw = "\"${inner.replace("\"", "\\\"")}\""
        val state = NotesEditorViewport.parseCaptureResult(raw)
        assertThat(state).isEqualTo(NotesEditorViewport.State(120.5, 10, 15))
    }

    @Test
    fun parseCaptureResultAllowsNullCaret() {
        val raw = "\"{\\\"scrollY\\\":40,\\\"caretStart\\\":null,\\\"caretEnd\\\":null}\""
        val state = NotesEditorViewport.parseCaptureResult(raw)
        assertThat(state).isEqualTo(NotesEditorViewport.State(40.0, null, null))
    }

    @Test
    fun parseCaptureResultRejectsNull() {
        assertThat(NotesEditorViewport.parseCaptureResult("null")).isNull()
        assertThat(NotesEditorViewport.parseCaptureResult(null)).isNull()
        assertThat(NotesEditorViewport.parseCaptureResult("")).isNull()
    }

    @Test
    fun isApplySuccessAcceptsWebViewEncodings() {
        assertThat(NotesEditorViewport.isApplySuccess("true")).isTrue()
        assertThat(NotesEditorViewport.isApplySuccess("\"true\"")).isTrue()
        assertThat(NotesEditorViewport.isApplySuccess("false")).isFalse()
        assertThat(NotesEditorViewport.isApplySuccess(null)).isFalse()
    }

    @Test
    fun editorInnerHtmlExtractsBody() {
        val full = "<html><body><div id=\"editor\"><p>Hi</p></div></body></html>"
        assertThat(NotesEditorHtml.editorInnerHtml(full)).isEqualTo("<p>Hi</p>")
        assertThat(NotesEditorHtml.editorInnerHtml("<p>plain</p>")).isEqualTo("<p>plain</p>")
        assertThat(NotesEditorHtml.editorInnerHtml("  ")).isEqualTo("<p><br></p>")
    }
}
