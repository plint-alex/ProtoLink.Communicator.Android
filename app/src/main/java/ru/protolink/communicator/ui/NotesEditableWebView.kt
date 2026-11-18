package ru.protolink.communicator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Notes body WebView that Android treats as a text editor and that actively requests the IME.
 *
 * Stock [WebView] inside Compose [androidx.compose.ui.viewinterop.AndroidView] often returns
 * false from [onCheckIsTextEditor], so taps on `#editor contenteditable` never open the IME.
 * Returning true gets `mServedView` but on some OEMs (Huawei/Honor ADY-LX9) Chromium still never
 * sets `mShowRequested` — call [NotesWebViewIme.requestShow] after touch / focus.
 */
class NotesEditableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasFocus()) {
                    requestFocus(FOCUS_DOWN)
                }
            }
            MotionEvent.ACTION_UP -> {
                // After Chromium places the caret from this gesture, request IME + DOM focus.
                post { NotesWebViewIme.requestShow(this) }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            post { NotesWebViewIme.requestShow(this) }
        }
    }
}
