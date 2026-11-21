package ru.protolink.communicator.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Cheap document length for Sync snapshot / length probes.
 *
 * Win SnapshotFs uses [FileInfo.Length] — never opens file content for size-only snaps.
 * Android 1.0.104 still opened every SAF document (AFD / full [readBytes]) during
 * `computeContentHashes=false`, starving Force/AutoPush before first Api on MyNotesFolder
 * (FAIL_CLASS FORCE_UPLOAD_STUCK_NO_PROGRESS / sticky Uploading).
 *
 * Prefer listed COLUMN_SIZE → primary [File.length] → document SIZE query → AFD length.
 * Never fall back to reading the whole stream for length.
 */
object SafDocumentSize {
    fun lengthBytes(
        context: Context,
        uri: Uri,
        primaryFile: File? = null,
        listedSize: Long? = null
    ): Long {
        if (listedSize != null && listedSize >= 0L) return listedSize
        primaryFile?.takeIf { it.isFile }?.let { return it.length() }
        queryColumnSize(context, uri)?.let { return it }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
                val len = pfd.length
                if (len >= 0L) return len
            }
        }
        return 0L
    }

    private fun queryColumnSize(context: Context, uri: Uri): Long? {
        val projections = arrayOf(
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            arrayOf(OpenableColumns.SIZE)
        )
        for (projection in projections) {
            val size = runCatching {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val idx = cursor.getColumnIndex(projection[0])
                    if (idx < 0 || cursor.isNull(idx)) return@use null
                    cursor.getLong(idx).takeIf { it >= 0L }
                }
            }.getOrNull()
            if (size != null) return size
        }
        return null
    }
}
