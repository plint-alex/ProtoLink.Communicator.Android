package ru.protolink.communicator.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * Ensures soft keyboard attaches to the Notes body [WebView] after a user tap.
 *
 * [onCheckIsTextEditor] alone can make IMM set `mServedView` without ever requesting show
 * (`mShowRequested=false` / `mInputShown=false` on Huawei ADY-LX9 + Yandex IME). Chromium also
 * needs `#editor` DOM focus so [WebView.onCreateInputConnection] wires to contenteditable —
 * otherwise keystrokes / `adb input text` are no-ops even when the View is focused.
 */
object NotesWebViewIme {
    /** Focus `#editor` and place a caret when the selection is outside it. */
    val FOCUS_EDITOR_JS: String =
        "(function(){" +
            "var e=document.getElementById('editor');" +
            "if(!e)return false;" +
            "e.focus();" +
            "var sel=window.getSelection&&window.getSelection();" +
            "if(!sel)return true;" +
            "if(sel.rangeCount===0||!e.contains(sel.anchorNode)){" +
            "var r=document.createRange();" +
            "r.selectNodeContents(e);" +
            "r.collapse(false);" +
            "sel.removeAllRanges();" +
            "sel.addRange(r);" +
            "}" +
            "return true;" +
            "})();"

    /**
     * Request IME for [web] after DOM focus script. Safe to call multiple times (touch + bridge).
     * @return true if [InputMethodManager.showSoftInput] reported success
     */
    fun requestShow(web: WebView): Boolean {
        if (!web.hasFocus()) {
            web.requestFocus(View.FOCUS_DOWN)
        }
        web.evaluateJavascript(FOCUS_EDITOR_JS, null)
        val imm = web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        imm.restartInput(web)
        return imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
    }
}
