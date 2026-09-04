package ru.protolink.communicator.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Reliable SAF children listing via DocumentsContract (DocumentFile.listFiles is flaky on some OEMs). */
object SafTreeLister {
    private const val TAG = "ProtoLinkNotes"

    data class Entry(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val uri: Uri
    )

    fun treeDocumentId(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String?): List<Entry> {
        val parentId = parentDocumentId ?: treeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = mutableListOf<Entry>()
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                    val mime = cursor.getString(mimeIdx).orEmpty()
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(Entry(id, name, isDir, documentUri(treeUri, id)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listChildren failed for $parentId", e)
        }
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun findIndexHtml(entries: List<Entry>): Entry? {
        val files = entries.filter { !it.isDirectory }
        return files.firstOrNull { it.name.equals("index.html", true) }
            ?: files.firstOrNull { it.name.equals("index.htm", true) }
            ?: files.firstOrNull { it.name.endsWith(".html", true) || it.name.endsWith(".htm", true) }
            // Some OEMs mis-label HTML as a directory MIME — still prefer by name
            ?: entries.firstOrNull { it.name.equals("index.html", true) }
            ?: entries.firstOrNull { it.name.equals("index.htm", true) }
    }

    /**
     * When [listChildren] returns no files (common on some OEMs for leaf folders that only contain
     * index.html), guess the document id and probe openability.
     */
    fun findIndexHtmlByGuess(context: Context, treeUri: Uri, folderDocumentId: String): Uri? {
        val suffixes = listOf("index.html", "index.htm", "Index.html", "Index.htm")
        val base = folderDocumentId.trimEnd('/')
        for (name in suffixes) {
            val candidates = listOf(
                "$base/$name",
                "$base%2F$name",
                // Some providers keep a trailing slash on folder ids
                "$folderDocumentId/$name"
            )
            for (id in candidates.distinct()) {
                val uri = documentUri(treeUri, id)
                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        Log.i(TAG, "guessed index ok: $id")
                        return uri
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "guess miss $id: ${e.message}")
                }
            }
        }
        return null
    }

    fun resolveIndexHtml(context: Context, treeUri: Uri, folderDocumentId: String): Uri? {
        val listed = findIndexHtml(listChildren(context, treeUri, folderDocumentId))?.uri
        if (listed != null) {
            try {
                context.contentResolver.openInputStream(listed)?.use { return listed }
            } catch (e: Exception) {
                Log.w(TAG, "listed index not readable: $listed", e)
            }
        }
        return findIndexHtmlByGuess(context, treeUri, folderDocumentId)
    }
}
