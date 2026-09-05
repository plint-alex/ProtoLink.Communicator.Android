package ru.protolink.communicator.ui

import com.google.gson.Gson

/** Decode WebView evaluateJavascript result (JSON-encoded string), matching Windows CoreWebView2. */
fun decodeEvaluateJavascriptString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching {
        Gson().fromJson(raw, String::class.java)
    }.getOrNull().orEmpty().ifBlank {
        // Fallback if value was already unquoted
        raw.removeSurrounding("\"")
            .replace("\\u003C", "<").replace("\\u003c", "<")
            .replace("\\u003E", ">").replace("\\u003e", ">")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }
}

/** Heal notes previously saved with literal \\u003C escapes from a bad Save path. */
fun healJsEscapedHtml(html: String): String {
    if (!html.contains("\\u003C") && !html.contains("\\u003c")) return html
    return html
        .replace("\\u003C", "<").replace("\\u003c", "<")
        .replace("\\u003E", ">").replace("\\u003e", ">")
        .replace("\\u0022", "\"")
        .replace("\\/", "/")
}
