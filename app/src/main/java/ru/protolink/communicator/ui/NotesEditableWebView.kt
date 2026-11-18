package ru.protolink.communicator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Notes body WebView that Android treats as a text editor.
 *
 * Stock [WebView] inside Compose [androidx.compose.ui.viewinterop.AndroidView] often returns
 * false from [onCheckIsTextEditor], so taps on `#editor contenteditable` never open the IME
 * (caret may flash; typing does nothing). Huawei/Honor builds (e.g. ADY-LX9) are especially
 * strict. Request focus on touch so the soft keyboard can attach.
 */
class NotesEditableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                if (!hasFocus()) {
                    requestFocus(FOCUS_DOWN)
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
