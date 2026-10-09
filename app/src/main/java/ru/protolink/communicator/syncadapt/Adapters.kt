package ru.protolink.communicator.syncadapt

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SafTreeLister
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.AddParentRequest
import ru.protolink.communicator.data.api.AddValueRequest
import ru.protolink.communicator.data.api.DeleteEntityRequest
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.api.RemoveParentRequest
import ru.protolink.communicator.sync.engine.ContentHashUtil
import ru.protolink.communicator.sync.engine.PathUtil
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.ports.LocalFileSystem
import ru.protolink.communicator.sync.ports.RemoteCloud
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime

class ApiRemoteCloud(private val api: ProtoLinkApi) : RemoteCloud {
    override suspend fun listChildrenPaged(parentId: String): List<RemoteEntry> {
        val all = mutableListOf<RemoteEntry>()
        var skip = 0
        val take = 200
        while (true) {
            val page = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(parentId), skip = skip, take = take, includeValues = true)
            )
            for (e in page) {
                val isFolder = e.code == CloudCodes.CLOUD_FOLDER
                val isFile = e.code == CloudCodes.CLOUD_FILE
                if (!isFolder && !isFile) continue
                val name = displayName(e) ?: continue
                if (isFile && name.equals("file-${e.id.replace("-", "")}", true)) continue
                all.add(
                    RemoteEntry(
                        id = e.id,
                        parentId = parentId,
                        name = name,
                        isFolder = isFolder,
                        updateTime = parseTime(e.updateTime)
                    )
                )
            }
            if (page.size < take) break
            skip += take
        }
        return all
    }

    override suspend fun addFolder(parentId: String, name: String): String {
        val res = api.addEntity(
            AddEntityRequest(
                code = CloudCodes.CLOUD_FOLDER,
                parentIds = listOf(parentId),
                values = listOf(AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
            )
        )
        return res.id ?: error("AddEntity folder returned empty id")
    }

    override suspend fun addFile(parentId: String, name: String, bytes: ByteArray, mimeType: String): String {
        val res = api.addEntity(
            AddEntityRequest(
                code = CloudCodes.CLOUD_FILE,
                parentIds = listOf(parentId),
                values = listOf(AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
            )
        )
        val id = res.id ?: error("AddEntity file returned empty id")
        uploadFile(id, name, bytes, mimeType)
        return id
    }

    override suspend fun uploadFile(entityId: String, name: String, bytes: ByteArray, mimeType: String) {
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("File", name, body)
        val idBody = entityId.toRequestBody("text/plain".toMediaTypeOrNull())
        api.addFile(idBody, part)
        // Always re-assert name after blob upload so entity UpdateTime bumps even when
        // server-side mime/name rewrite in addFile is skipped or fails silently.
        // Other clients (Windows) use UpdateTime + Content-Length to decide Read.
        if (name.isNotBlank() && !name.equals("file-${entityId.replace("-", "")}", ignoreCase = true)) {
            api.addValue(entityId, AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
        }
    }

    override suspend fun downloadFile(entityId: String): ByteArray? {
        val resp = api.getFile(entityId)
        if (!resp.isSuccessful) return null
        return resp.body()?.bytes()
    }

    override suspend fun fileSize(entityId: String): Long? {
        // Headers only. OpenResty often omits Content-Length (chunked) — return null then.
        // Never read the body here: enrich-all-files stalled mapped sync forever.
        // Same-size edits use ContentHash / unseeded remote hash probe in SyncEngine instead.
        val resp = api.getFile(entityId)
        if (!resp.isSuccessful) return null
        val body = resp.body() ?: return null
        return try {
            val len = body.contentLength()
            if (len > 0L) len else null
        } finally {
            body.close()
        }
    }

    override suspend fun rename(entityId: String, newName: String) {
        api.addValue(entityId, AddValueRequest(value = newName, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
    }

    override suspend fun move(entityId: String, oldParentId: String, newParentId: String) {
        api.removeParent(RemoveParentRequest(entityId, oldParentId))
        api.addParent(AddParentRequest(entityId, newParentId))
    }

    override suspend fun delete(entityId: String) {
        api.deleteEntity(DeleteEntityRequest(entityId))
    }

    private fun displayName(e: ru.protolink.communicator.data.api.EntityDto): String? {
        val values = e.values ?: return null
        for (v in values) {
            val s = v.value?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.NAME_TYPE_ID, true) } == true) return s
        }
        for (v in values) {
            val s = v.value?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } == true) continue
            return s
        }
        return null
    }

    private fun parseTime(s: String?): Instant? =
        s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}

/** Local FS over a persisted SAF tree URI (rootPath = content://... tree). */
class SafLocalFileSystem(private val context: Context) : LocalFileSystem {
    private fun treeUri(rootPath: String): Uri = Uri.parse(rootPath)

    /**
     * Real byte length for a SAF document. Do **not** use [InputStream.available] — on many OEMs
     * (Huawei leaf folders) it returns 0 for existing HTML files, which made sync treat notes as empty.
     */
    private fun uriByteLength(uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
                val len = pfd.length
                if (len >= 0L) return len
            }
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().size.toLong() } ?: 0L
        }.getOrDefault(0L)
    }

    private fun uriContentHash(uri: Uri): String =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ContentHashUtil.sha256Hex(it.readBytes()) } ?: ""
        }.getOrDefault("")

    private fun root(rootPath: String): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri(rootPath))
            ?: error("Invalid tree URI $rootPath")

    private fun find(rootPath: String, relativePath: String): DocumentFile? {
        val tree = treeUri(rootPath)
        val n = relativePath.replace('\\', '/').trim('/')
        if (n.isEmpty()) return root(rootPath)

        // Prefer DocumentsContract ids — DocumentFile.listFiles misses leaf files on some OEMs.
        val docId = NotesTreeBuilder.folderDocumentId(tree, n)
        val byId = DocumentFile.fromSingleUri(context, SafTreeLister.documentUri(tree, docId))
        if (byId != null && byId.exists()) return byId

        var cur: DocumentFile? = root(rootPath)
        for (part in n.split('/').filter { it.isNotEmpty() }) {
            val listed = cur?.let { parent ->
                val parentId = runCatching { DocumentsContract.getDocumentId(parent.uri) }.getOrNull()
                if (parentId != null) {
                    SafTreeLister.listChildren(context, tree, parentId)
                        .firstOrNull { it.name.equals(part, true) }
                        ?.let { DocumentFile.fromSingleUri(context, it.uri) }
                } else null
            }
            cur = listed
                ?: cur?.listFiles()?.firstOrNull { it.name?.equals(part, true) == true }
            if (cur == null) return null
        }
        return cur
    }

    override fun snapshot(rootPath: String): List<FsEntry> {
        val tree = treeUri(rootPath)
        val out = mutableListOf<FsEntry>()
        fun walk(folderDocId: String, rel: String) {
            val entries = SafTreeLister.listChildren(context, tree, folderDocId)
            // OEM leaf bug: children query may omit files — probe index.html explicitly.
            val hasIndexListed = entries.any {
                !it.isDirectory && (
                    it.name.equals("index.html", true) || it.name.equals("index.htm", true)
                )
            }
            if (!hasIndexListed) {
                SafTreeLister.findIndexHtmlByGuess(context, tree, folderDocId)?.let { indexUri ->
                    val name = "index.html"
                    val childRel = if (rel.isEmpty()) name.lowercase() else "$rel/${name.lowercase()}"
                    out.add(FsEntry(childRel, false, uriByteLength(indexUri), uriContentHash(indexUri)))
                }
            }
            for (child in entries) {
                val childRel = if (rel.isEmpty()) child.name.lowercase() else "$rel/${child.name.lowercase()}"
                if (child.isDirectory) {
                    val size = sumFilesSaf(tree, child.documentId)
                    out.add(FsEntry(childRel, true, size))
                    walk(child.documentId, childRel)
                } else {
                    out.add(FsEntry(childRel, false, uriByteLength(child.uri), uriContentHash(child.uri)))
                }
            }
        }
        walk(SafTreeLister.treeDocumentId(tree), "")
        return out
    }

    private fun sumFilesSaf(tree: Uri, folderDocId: String): Long {
        var sum = 0L
        val entries = SafTreeLister.listChildren(context, tree, folderDocId)
        val hasIndex = entries.any {
            !it.isDirectory && (it.name.equals("index.html", true) || it.name.equals("index.htm", true))
        }
        if (!hasIndex) {
            SafTreeLister.findIndexHtmlByGuess(context, tree, folderDocId)?.let { uri ->
                sum += uriByteLength(uri)
            }
        }
        for (child in entries) {
            sum += if (child.isDirectory) {
                sumFilesSaf(tree, child.documentId)
            } else {
                uriByteLength(child.uri)
            }
        }
        return sum
    }

    override fun createDirectory(rootPath: String, relativePath: String) {
        val n = relativePath.replace('\\', '/').trim('/')
        val tree = treeUri(rootPath)
        var curId = SafTreeLister.treeDocumentId(tree)
        for (part in n.split('/').filter { it.isNotEmpty() }) {
            val existing = SafTreeLister.listChildren(context, tree, curId)
                .firstOrNull { it.isDirectory && it.name.equals(part, true) }
            if (existing != null) {
                curId = existing.documentId
                continue
            }
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                SafTreeLister.documentUri(tree, curId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                part
            ) ?: error("Cannot create $part")
            curId = DocumentsContract.getDocumentId(created)
        }
    }

    override fun writeFile(rootPath: String, relativePath: String, bytes: ByteArray) {
        val parent = PathUtil.parentOf(relativePath)
        if (parent.isNotEmpty()) createDirectory(rootPath, parent)
        val name = PathUtil.nameOf(relativePath)
        val tree = treeUri(rootPath)
        val parentId = if (parent.isEmpty()) {
            SafTreeLister.treeDocumentId(tree)
        } else {
            NotesTreeBuilder.folderDocumentId(tree, parent)
        }
        // Prefer open-by-guess / listed file so we don't create duplicates when listFiles is blind.
        val existingUri = SafTreeLister.listChildren(context, tree, parentId)
            .firstOrNull { !it.isDirectory && it.name.equals(name, true) }
            ?.uri
            ?: if (name.equals("index.html", true) || name.equals("index.htm", true)) {
                SafTreeLister.findIndexHtmlByGuess(context, tree, parentId)
            } else {
                val guessId = "$parentId/$name"
                val uri = SafTreeLister.documentUri(tree, guessId)
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { uri }
                }.getOrNull()
            }

        val target = existingUri ?: DocumentsContract.createDocument(
            context.contentResolver,
            SafTreeLister.documentUri(tree, parentId),
            mimeForName(name),
            name
        ) ?: error("Cannot create $name")

        context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
    }

    override fun readFile(rootPath: String, relativePath: String): ByteArray {
        val tree = treeUri(rootPath)
        val n = relativePath.replace('\\', '/').trim('/')
        val parent = PathUtil.parentOf(n)
        val name = PathUtil.nameOf(n)
        val parentId = if (parent.isEmpty()) {
            SafTreeLister.treeDocumentId(tree)
        } else {
            NotesTreeBuilder.folderDocumentId(tree, parent)
        }
        val uri = SafTreeLister.listChildren(context, tree, parentId)
            .firstOrNull { !it.isDirectory && it.name.equals(name, true) }
            ?.uri
            ?: if (name.equals("index.html", true) || name.equals("index.htm", true)) {
                SafTreeLister.findIndexHtmlByGuess(context, tree, parentId)
            } else {
                val guess = SafTreeLister.documentUri(tree, "$parentId/$name")
                runCatching {
                    context.contentResolver.openInputStream(guess)?.use { guess }
                }.getOrNull()
            }
            ?: find(rootPath, n)?.uri
            ?: error("Missing $relativePath")
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    override fun delete(rootPath: String, relativePath: String, isFolder: Boolean) {
        val tree = treeUri(rootPath)
        val n = relativePath.replace('\\', '/').trim('/')
        if (n.isEmpty()) error("Cannot delete tree root")

        val found = find(rootPath, n)
        val docId = found?.let { runCatching { DocumentsContract.getDocumentId(it.uri) }.getOrNull() }
            ?: NotesTreeBuilder.folderDocumentId(tree, n)

        if (isFolder) {
            deleteDocumentRecursive(tree, docId)
        } else {
            val uri = found?.uri ?: SafTreeLister.documentUri(tree, docId)
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                found?.delete()
            }
        }
    }

    /**
     * Bottom-up delete: children first (OEM [DocumentFile.delete] often leaves nested files),
     * then the folder itself. Also probes guessed index.html when listChildren is blind.
     */
    private fun deleteDocumentRecursive(tree: Uri, documentId: String) {
        val children = SafTreeLister.listChildren(context, tree, documentId)
        for (child in children) {
            if (child.isDirectory) {
                deleteDocumentRecursive(tree, child.documentId)
            } else {
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, child.uri)
                }
            }
        }
        // Leaf OEM quirk: children query may omit index.html — remove by guess before folder.
        SafTreeLister.findIndexHtmlByGuess(context, tree, documentId)?.let { indexUri ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, indexUri) }
        }
        val folderUri = SafTreeLister.documentUri(tree, documentId)
        val ok = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, folderUri)
        }.getOrDefault(false)
        if (!ok) {
            DocumentFile.fromSingleUri(context, folderUri)?.delete()
        }
    }

    override fun move(rootPath: String, fromRelative: String, toRelative: String, isFolder: Boolean) {
        if (isFolder) {
            val from = find(rootPath, fromRelative) ?: return
            createDirectory(rootPath, toRelative)
            copyTree(from, toRelative, rootPath)
            from.delete()
        } else {
            val bytes = readFile(rootPath, fromRelative)
            writeFile(rootPath, toRelative, bytes)
            delete(rootPath, fromRelative, false)
        }
    }

    private fun copyTree(from: DocumentFile, toRel: String, rootPath: String) {
        val tree = treeUri(rootPath)
        val fromId = runCatching { DocumentsContract.getDocumentId(from.uri) }.getOrNull()
        val children = if (fromId != null) {
            SafTreeLister.listChildren(context, tree, fromId).map { it.name to (it.isDirectory to it.uri) }
        } else {
            from.listFiles().mapNotNull { c ->
                val name = c.name ?: return@mapNotNull null
                name to (c.isDirectory to c.uri)
            }
        }
        for ((name, pair) in children) {
            val (isDir, uri) = pair
            val dest = PathUtil.join(toRel, name)
            if (isDir) {
                createDirectory(rootPath, dest)
                DocumentFile.fromSingleUri(context, uri)?.let { copyTree(it, dest, rootPath) }
            } else {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                writeFile(rootPath, dest, bytes)
            }
        }
        // Leaf index omitted from listing
        if (fromId != null) {
            val listedNames = children.map { it.first.lowercase() }.toSet()
            if ("index.html" !in listedNames && "index.htm" !in listedNames) {
                SafTreeLister.findIndexHtmlByGuess(context, tree, fromId)?.let { indexUri ->
                    val bytes = context.contentResolver.openInputStream(indexUri)!!.use { it.readBytes() }
                    writeFile(rootPath, PathUtil.join(toRel, "index.html"), bytes)
                }
            }
        }
    }

    override fun exists(rootPath: String, relativePath: String): Boolean {
        if (find(rootPath, relativePath) != null) return true
        val tree = treeUri(rootPath)
        val n = relativePath.replace('\\', '/').trim('/')
        val uri = SafTreeLister.documentUri(tree, NotesTreeBuilder.folderDocumentId(tree, n))
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun mimeForName(name: String): String = when {
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".txt", true) -> "text/plain"
        else -> "application/octet-stream"
    }
}

