package ru.protolink.communicator.util

/**
 * Mime / extension → Cloud list type cue (AT-UL3). Offline-pure; no API.
 * Prefer extension short label; fall back to mime family or "File".
 * Mirrors Windows CloudFileTypeCue.
 */
object CloudFileTypeCue {
    fun shortLabel(mimeType: String?, fileName: String?): String {
        extensionLabel(fileName)?.let { return it }

        if (mimeType.isNullOrBlank()) return "File"

        val mime = mimeType.trim()
        if (mime.startsWith("image/", ignoreCase = true)) return "Image"
        if (mime.startsWith("video/", ignoreCase = true)) return "Video"
        if (mime.startsWith("audio/", ignoreCase = true)) return "Audio"
        if (mime.equals("application/pdf", ignoreCase = true)) return "PDF"
        if (mime.equals("application/zip", ignoreCase = true)
            || mime.equals("application/x-zip-compressed", ignoreCase = true)
        ) {
            return "ZIP"
        }
        if (mime.equals("application/json", ignoreCase = true)) return "JSON"
        if (mime.startsWith("text/", ignoreCase = true)) return "Text"

        val slash = mime.lastIndexOf('/')
        if (slash >= 0 && slash < mime.length - 1) {
            var sub = mime.substring(slash + 1)
            val plus = sub.indexOf('+')
            if (plus > 0) sub = sub.substring(0, plus)
            if (sub.length in 1..12
                && !sub.equals("octet-stream", ignoreCase = true)
            ) {
                return sub.uppercase()
            }
        }

        return "File"
    }

    fun looksLikeImage(mimeType: String?, fileName: String?): Boolean =
        MessengerAttachmentRender.looksLikeImageMime(mimeType) || looksLikeImageExtension(fileName)

    private fun extensionLabel(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot < 0 || dot >= trimmed.length - 1) return null
        val label = trimmed.substring(dot + 1)
        if (label.isEmpty() || label.length > 8) return null
        return label.uppercase()
    }

    private fun looksLikeImageExtension(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot < 0 || dot >= trimmed.length - 1) return false
        return when (trimmed.substring(dot + 1).lowercase()) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> true
            else -> false
        }
    }
}
