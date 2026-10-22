package ru.protolink.communicator.util

/**
 * Mime → bubble kind (client-contract §6 / M6). Image only after getImage returns image bytes;
 * "Send as file" forces file row on the sender without changing stored mime.
 */
enum class MessengerAttachmentVisualKind {
    Loading,
    Image,
    File,
    TombstoneRemoved,
    TombstoneNoAccess
}

object MessengerAttachmentRender {
    const val TOMBSTONE_REMOVED_EN = "File removed"
    const val TOMBSTONE_REMOVED_RU = "Файл удалён"
    const val TOMBSTONE_NO_ACCESS_EN = "You don't have access to this item"
    const val TOMBSTONE_NO_ACCESS_RU = "У вас нет доступа к этому элементу"
    const val CAP_REFUSE_EN = "Up to 10 files per message"
    const val DOWNLOAD_FAILED_EN = "Couldn't download the file"

    fun looksLikeImageMime(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        return mimeType.startsWith("image/", ignoreCase = true)
    }

    /**
     * Initial kind before network probe. [forceFileRow] is the composer "Send as file" flag (not on the wire).
     */
    fun initialKind(mimeType: String?, forceFileRow: Boolean): MessengerAttachmentVisualKind {
        if (forceFileRow) return MessengerAttachmentVisualKind.File
        if (looksLikeImageMime(mimeType)) return MessengerAttachmentVisualKind.Loading
        return MessengerAttachmentVisualKind.File
    }

    /**
     * After getImage (or getFile probe). Non-image content-type or decode failure → file row (AT-M20).
     */
    fun afterImageProbe(
        forceFileRow: Boolean,
        httpStatus: Int?,
        contentType: String?,
        decodedAsImage: Boolean
    ): MessengerAttachmentVisualKind {
        if (forceFileRow) return MessengerAttachmentVisualKind.File
        if (httpStatus == 404) return MessengerAttachmentVisualKind.TombstoneRemoved
        if (httpStatus == 403) return MessengerAttachmentVisualKind.TombstoneNoAccess
        if (httpStatus != null && httpStatus in 200..299 && decodedAsImage && isImageContentType(contentType)) {
            return MessengerAttachmentVisualKind.Image
        }
        return MessengerAttachmentVisualKind.File
    }

    fun isImageContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        var ct = contentType
        val semi = ct.indexOf(';')
        if (semi >= 0) ct = ct.substring(0, semi)
        ct = ct.trim()
        return ct.startsWith("image/", ignoreCase = true)
    }

    fun <T> splitBatches(items: List<T>, maxPerBatch: Int): List<List<T>> {
        require(maxPerBatch > 0) { "maxPerBatch must be > 0" }
        if (items.isEmpty()) return emptyList()
        val batches = mutableListOf<List<T>>()
        var i = 0
        while (i < items.size) {
            val end = minOf(i + maxPerBatch, items.size)
            batches.add(items.subList(i, end))
            i = end
        }
        return batches
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) {
            return if (kb >= 100) String.format("%.0f KB", kb)
            else trimTrailingZeros(String.format("%.1f", kb)) + " KB"
        }
        val mb = kb / 1024.0
        return if (mb >= 10) String.format("%.1f MB", mb)
        else trimTrailingZeros(String.format("%.2f", mb)) + " MB"
    }

    private fun trimTrailingZeros(s: String): String =
        s.trimEnd('0').trimEnd('.')

    fun tombstoneRemovedLabel(isRu: Boolean): String =
        if (isRu) TOMBSTONE_REMOVED_RU else TOMBSTONE_REMOVED_EN

    fun tombstoneNoAccessLabel(isRu: Boolean): String =
        if (isRu) TOMBSTONE_NO_ACCESS_RU else TOMBSTONE_NO_ACCESS_EN
}
