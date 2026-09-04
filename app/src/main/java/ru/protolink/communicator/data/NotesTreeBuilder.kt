package ru.protolink.communicator.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Notes folder tree (Windows parity): every directory is a page.
 *
 * Child listing uses [SafTreeLister] (DocumentsContract). DocumentFile.listFiles() on some OEMs
 * (e.g. Huawei) omits files in leaf folders that only contain index.html, which made those pages
 * look empty and caused sync to miss real content.
 */
object NotesTreeBuilder {
    private const val TAG = "ProtoLinkNotes"

    data class Node(
        val documentId: String,
        val safDocumentId: String,
        val name: String,
        val relativePath: String,
        val folderUri: Uri,
        val indexUri: Uri?,
        val children: List<Node>
    )

    fun build(context: Context, treeUri: Uri): Node {
        val rootId = SafTreeLister.treeDocumentId(treeUri)
        val rootUri = SafTreeLister.documentUri(treeUri, rootId)
        val rootName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?.takeIf { it.isNotBlank() }
            ?: "Notes"
        return buildFromSaf(context, treeUri, rootId, rootUri, rootName, relativePath = "")
    }

    private fun buildFromSaf(
        context: Context,
        treeUri: Uri,
        folderDocId: String,
        folderUri: Uri,
        name: String,
        relativePath: String
    ): Node {
        val entries = SafTreeLister.listChildren(context, treeUri, folderDocId)
        val dirs = entries.filter { it.isDirectory }
        val indexUri = SafTreeLister.resolveIndexHtml(context, treeUri, folderDocId)
        Log.i(
            TAG,
            "scan \"$relativePath\"/$name → ${dirs.size} dirs, files=${entries.count { !it.isDirectory }}, index=${indexUri != null}"
        )
        val childNodes = dirs
            .sortedBy { it.name.lowercase() }
            .map { child ->
                val childRel = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
                buildFromSaf(context, treeUri, child.documentId, child.uri, child.name, childRel)
            }
        return Node(
            documentId = folderUri.toString(),
            safDocumentId = folderDocId,
            name = name,
            relativePath = relativePath,
            folderUri = folderUri,
            indexUri = indexUri,
            children = childNodes
        )
    }

    fun flattenVisible(root: Node, expandedIds: Set<String>): List<VisibleRow> {
        val out = mutableListOf<VisibleRow>()
        fun walk(node: Node, depth: Int) {
            out.add(
                VisibleRow(
                    documentId = node.documentId,
                    safDocumentId = node.safDocumentId,
                    name = node.name,
                    relativePath = node.relativePath,
                    depth = depth,
                    hasChildren = node.children.isNotEmpty(),
                    expanded = expandedIds.contains(node.documentId),
                    folderUri = node.folderUri.toString(),
                    indexUri = node.indexUri?.toString()
                )
            )
            if (expandedIds.contains(node.documentId)) {
                for (child in node.children) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun findNode(root: Node, documentId: String): Node? {
        if (root.documentId == documentId) return root
        for (c in root.children) {
            findNode(c, documentId)?.let { return it }
        }
        return null
    }

    data class VisibleRow(
        val documentId: String,
        val safDocumentId: String,
        val name: String,
        val relativePath: String,
        val depth: Int,
        val hasChildren: Boolean,
        val expanded: Boolean,
        val folderUri: String,
        val indexUri: String?
    )

    /**
     * Resolve index.html for a folder. Prefers cached URI, then DocumentsContract listing/guess,
     * then DocumentFile walk (last resort).
     */
    fun resolveIndex(
        context: Context,
        treeUri: Uri,
        relativePath: String,
        safDocumentId: String?,
        cachedIndexUri: String?
    ): Uri? {
        fun readable(uri: Uri): Boolean =
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }.getOrDefault(false)

        cachedIndexUri?.let { Uri.parse(it) }?.takeIf { readable(it) }?.let { return it }

        val folderId = safDocumentId?.takeIf { it.isNotBlank() }
            ?: folderDocumentId(treeUri, relativePath)
        SafTreeLister.resolveIndexHtml(context, treeUri, folderId)?.let { return it }

        return resolveIndexViaDocumentFile(context, treeUri, relativePath)?.takeIf { readable(it) }
    }

    fun folderDocumentId(treeUri: Uri, relativePath: String): String {
        val rootId = SafTreeLister.treeDocumentId(treeUri)
        val rel = relativePath.replace('\\', '/').trim('/')
        return if (rel.isEmpty()) rootId else "$rootId/$rel"
    }

    /** Resolve index.html for a folder by walking DocumentFile from the tree root. */
    fun resolveIndexViaDocumentFile(
        context: Context,
        treeUri: Uri,
        relativePath: String
    ): Uri? {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (relativePath.isNotBlank()) {
            for (seg in relativePath.split('/').filter { it.isNotEmpty() }) {
                dir = dir.listFiles().firstOrNull { it.isDirectory && it.name.equals(seg, true) }
                    ?: return null
            }
        }
        val files = dir.listFiles().toList()
        val index = files.firstOrNull {
            val n = it.name ?: return@firstOrNull false
            n.equals("index.html", true) || n.equals("index.htm", true)
        } ?: files.firstOrNull {
            val n = it.name ?: return@firstOrNull false
            (n.endsWith(".html", true) || n.endsWith(".htm", true)) && !it.isDirectory
        }
        Log.i(TAG, "resolveIndex DocumentFile path=\"$relativePath\" → ${index?.uri}")
        return index?.uri
    }

    fun readUtf8(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }.orEmpty()

    /** Create index.html under a folder when missing (Windows creates on new folder; we need it for Save). */
    fun ensureIndexHtml(
        context: Context,
        treeUri: Uri,
        folderSafDocumentId: String,
        initialHtml: String
    ): Uri? {
        SafTreeLister.resolveIndexHtml(context, treeUri, folderSafDocumentId)?.let { return it }
        return try {
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                SafTreeLister.documentUri(treeUri, folderSafDocumentId),
                "text/html",
                "index.html"
            ) ?: return null
            context.contentResolver.openOutputStream(created, "wt")?.use { out ->
                out.write(initialHtml.toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "created index.html under $folderSafDocumentId")
            created
        } catch (e: Exception) {
            Log.e(TAG, "ensureIndexHtml failed for $folderSafDocumentId", e)
            null
        }
    }
}
