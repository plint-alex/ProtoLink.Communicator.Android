package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecodeEvaluateJavascriptStringTest {
    @Test
    fun decodesUnicodeEscapedHtmlFromWebView() {
        val raw = "\"\\u003Ch1>Films\\u003C/h1> \\u003Cp>Test3\\u003C/p>\""
        assertThat(decodeEvaluateJavascriptString(raw))
            .isEqualTo("<h1>Films</h1> <p>Test3</p>")
    }

    @Test
    fun healLiteralEscapesOnDisk() {
        val bad = """\u003Ch1>Films\u003C/h1> \u003Cp>Test3\u003C/p>"""
        assertThat(healJsEscapedHtml(bad)).isEqualTo("<h1>Films</h1> <p>Test3</p>")
        assertThat(healJsEscapedHtml("<h1>ok</h1>")).isEqualTo("<h1>ok</h1>")
    }
}
