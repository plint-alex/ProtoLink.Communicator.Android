package ru.protolink.communicator.syncadapt

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.withTimeoutOrNull
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SafChildResolve
import ru.protolink.communicator.data.SafPrimaryPath
import ru.protolink.communicator.data.SafTreeLister
import java.io.File
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.AddParentRequest
import ru.protolink.communicator.data.api.AddValueRequest
import ru.protolink.communicator.data.api.DeleteEntityRequest
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.api.RemoveParentRequest
import ru.protolink.communicator.sync.engine.ContentHashUtil
import ru.protolink.communicator.sync.engine.PathUtil
import ru.protolink.communicator.sync.engine.SyncEngine
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.ports.LocalFileSystem
import ru.protolink.communicator.sync.ports.RemoteCloud
import ru.protolink.communicator.util.EntityMeta
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime

class ApiRemoteCloud(private val api: ProtoLinkApi) : RemoteCloud {
    override suspend fun listChildrenPaged(parentId: String, includeValues: Boolean): List<RemoteEntry> {
        return listChildrenOfParents(listOf(parentId), includeValues)
    }

    override suspend fun listChildrenOfParents(
        parentIds: List<String>,
        includeValues: Boolean
    ): List<RemoteEntry> {
        val distinct = parentIds.distinct().filter { it.isNotEmpty() }
        if (distinct.isEmpty()) return emptyList()
        val parentSet = distinct.toSet()
        val matchAny = distinct.size > 1
        val all = mutableListOf<RemoteEntry>()
        var skip = 0
        val take = 200
        while (true) {
            val page = api.getEntities(
                GetEntitiesRequest(
                    parentIds = distinct,
                    skip = skip,
                    take = take,
                    includeValues = includeValues,
                    parentIdsMatchAny = matchAny
                )
            )
            for (e in page) {
                val isFolder = e.code == CloudCodes.CLOUD_FOLDER
                val isFile = e.code == CloudCodes.CLOUD_FILE
                if (!isFolder && !isFile) continue
                // Same name resolver as Cloud UI (EntityMeta). Old adapter returned null and
                // skipped — Sync never walked folders Cloud listed → mapped ghosts (NFR-SYNC-1 /
                // #android-mapped-missing-local). Only skip synthetic unnamed CloudFiles when
                // Values were requested (Win parity). Structure-only lists keep synthetics so
                // warm WalkRemote can match meta by id.
                val name = EntityMeta.displayName(e)
                if (includeValues && EntityMeta.isSyntheticCloudFileDisplayName(e, name)) continue
                val parentId = e.mainParentId?.takeIf { it in parentSet }
                    ?: distinct.singleOrNull()
                    ?: continue
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

    override suspend fun getEntitiesByIds(ids: List<String>, includeValues: Boolean): List<RemoteEntry> {
        if (ids.isEmpty()) return emptyList()
        val all = mutableListOf<RemoteEntry>()
        val take = 200
        for (chunk in ids.distinct().chunked(take)) {
            var skip = 0
            while (true) {
                val page = api.getEntities(
                    GetEntitiesRequest(
                        ids = chunk,
                        skip = skip,
                        take = take,
                        includeValues = includeValues
                    )
                )
                for (e in page) {
                    val isFolder = e.code == CloudCodes.CLOUD_FOLDER
                    val isFile = e.code == CloudCodes.CLOUD_FILE
                    if (!isFolder && !isFile) continue
                    val name = EntityMeta.displayName(e)
                    if (includeValues && EntityMeta.isSyntheticCloudFileDisplayName(e, name)) continue
                    all.add(
                        RemoteEntry(
                            id = e.id,
                            parentId = "",
                            name = name,
                            isFolder = isFolder,
                            updateTime = parseTime(e.updateTime)
                        )
                    )
                }
                if (page.size < take) break
                skip += take
            }
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
        // Win RemoteDownloadBudget 45s — soft-fail null on timeout (do not abort reconcile).
        return withTimeoutOrNull(SyncEngine.remoteDownloadBudgetMs) {
            val resp = api.getFile(entityId)
            if (!resp.isSuccessful) return@withTimeoutOrNull null
            resp.body()?.bytes()
        }
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

        // Prefer DocumentsContract ids — DocumentFile.listFiles misses leaf files on some OEMs
        // and SingleDocumentFile.listFiles throws UnsupportedOperationException (Force abort after heal).
        val docId = NotesTreeBuilder.folderDocumentId(tree, n)
        val byId = DocumentFile.fromSingleUri(context, SafTreeLister.documentUri(tree, docId))
        if (byId != null && byId.exists()) return byId

        var curId = SafTreeLister.treeDocumentId(tree)
        for (part in n.split('/').filter { it.isNotEmpty() }) {
            val listed = SafTreeLister.listChildren(context, tree, curId)
                .map { it.name to it.documentId }
            val nextId = SafChildResolve.nextChildDocumentId(listed, part, curId) { guessId ->
                val uri = SafTreeLister.documentUri(tree, guessId)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc != null && doc.exists()
            } ?: return null
            curId = nextId
        }
        return DocumentFile.fromSingleUri(context, SafTreeLister.documentUri(tree, curId))
            ?.takeIf { it.exists() }
    }

    override fun snapshot(rootPath: String): List<FsEntry> {
        val tree = treeUri(rootPath)
        // Primary trees: flush MediaProvider so DocumentsContract sees adb mkdir (1.0.99 miss).
        SafPrimaryPath.flushListing(context, tree)
        val out = mutableListOf<FsEntry>()
        fun walk(folderDocId: String, rel: String) {
            val entries = SafTreeLister.listChildren(context, tree, folderDocId)
            val seenNames = entries.map { it.name.lowercase() }.toMutableSet()
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
                    seenNames.add(name)
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
            // Shell/MediaProvider creates under primary: can lag DocumentsContract — merge File kids
            // so ApplyLocal sees adb mkdir fixtures (Win File.Exists parity).
            mergePrimaryFileChildren(tree, rel, seenNames, out)
        }
        walk(SafTreeLister.treeDocumentId(tree), "")
        return out
    }

    /**
     * Append primary-tree children missing from DocumentsContract listing.
     * Uses [SafPrimaryPath.listChildNames] (File.list / Os.listdir) so scoped-storage null
     * listFiles still surfaces adb mkdir fixtures. Recurses into File-only folders.
     */
    private fun mergePrimaryFileChildren(
        tree: Uri,
        rel: String,
        seenNames: MutableSet<String>,
        out: MutableList<FsEntry>
    ) {
        val dir = SafPrimaryPath.tryResolveExisting(tree, rel) ?: return
        if (!dir.isDirectory) return
        val names = SafPrimaryPath.listChildNames(dir)
        if (names.isEmpty()) return
        for (rawName in names) {
            if (rawName == "." || rawName == "..") continue
            val nameKey = rawName.lowercase()
            if (nameKey in seenNames) continue
            seenNames.add(nameKey)
            val f = File(dir, rawName)
            val childRel = if (rel.isEmpty()) nameKey else "$rel/$nameKey"
            val isDir = f.isDirectory || runCatching {
                // Os.listdir may return a name while File metadata is briefly stale.
                SafPrimaryPath.listChildNames(f).isNotEmpty() && !f.isFile
            }.getOrDefault(false)
            if (isDir && !f.isFile) {
                out.add(FsEntry(childRel, true, if (f.isDirectory) sumFilesIo(f) else 0L))
                mergePrimaryFileChildren(tree, childRel, mutableSetOf(), out)
            } else if (f.isFile) {
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                out.add(
                    FsEntry(
                        childRel,
                        false,
                        bytes.size.toLong(),
                        ContentHashUtil.sha256Hex(bytes)
                    )
                )
            } else {
                // Name from Os.listdir but File can't read — still record as folder stub so
                // ApplyLocal Added can create the Api entity; nested merge may fill later.
                out.add(FsEntry(childRel, true, 0L))
                mergePrimaryFileChildren(tree, childRel, mutableSetOf(), out)
            }
        }
    }

    private fun sumFilesIo(dir: File): Long {
        var sum = 0L
        val kids = dir.listFiles() ?: return 0L
        for (f in kids) {
            sum += if (f.isDirectory) sumFilesIo(f) else f.length()
        }
        return sum
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

    override fun listImmediate(rootPath: String, relativePath: String): List<FsEntry> {
        val tree = treeUri(rootPath)
        val parentNorm = relativePath.replace('\\', '/').trim('/')
        SafPrimaryPath.flushListing(context, tree, parentNorm)
        val parentId = if (parentNorm.isEmpty()) {
            SafTreeLister.treeDocumentId(tree)
        } else {
            NotesTreeBuilder.folderDocumentId(tree, parentNorm)
        }
        val fromContract = SafTreeLister.listChildren(context, tree, parentId).map { child ->
            val childRel = if (parentNorm.isEmpty()) child.name else "$parentNorm/${child.name}"
            FsEntry(
                relativePath = childRel.replace('\\', '/'),
                isFolder = child.isDirectory,
                sizeBytes = 0L,
                contentHash = ""
            )
        }
        val seen = fromContract.map { PathUtil.nameOf(it.relativePath).lowercase() }.toMutableSet()
        val fromFile = mutableListOf<FsEntry>()
        val dir = SafPrimaryPath.tryResolveExisting(tree, parentNorm)
        if (dir != null && dir.isDirectory) {
            for (rawName in SafPrimaryPath.listChildNames(dir)) {
                if (rawName == "." || rawName == "..") continue
                val key = rawName.lowercase()
                if (key in seen) continue
                seen.add(key)
                val f = File(dir, rawName)
                val childRel = if (parentNorm.isEmpty()) rawName else "$parentNorm/$rawName"
                fromFile.add(
                    FsEntry(
                        relativePath = childRel.replace('\\', '/'),
                        isFolder = f.isDirectory || !f.isFile,
                        sizeBytes = 0L,
                        contentHash = ""
                    )
                )
            }
        }
        return fromContract + fromFile
    }

    override fun createDirectory(rootPath: String, relativePath: String) {
        val n = relativePath.replace('\\', '/').trim('/')
        if (n.isEmpty()) return
        val tree = treeUri(rootPath)
        var curId = SafTreeLister.treeDocumentId(tree)
        for (part in n.split('/').filter { it.isNotEmpty() }) {
            // Guess-first: Force materialize was spending ~6–7s/file on listChildren of wide parents.
            val guessed = SafChildResolve.nextChildDocumentId(
                emptyList(),
                part,
                curId
            ) { guessId ->
                val uri = SafTreeLister.documentUri(tree, guessId)
                DocumentFile.fromSingleUri(context, uri)?.exists() == true
            }
            if (guessed != null) {
                curId = guessed
                continue
            }
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
        // Guess-first then listChildren fallback — avoids O(parent width) query per Force file.
        val guessId = SafChildResolve.nextChildDocumentId(
            emptyList(),
            name,
            parentId
        ) { id ->
            val uri = SafTreeLister.documentUri(tree, id)
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        }
        val existingUri = guessId?.let { SafTreeLister.documentUri(tree, it) }
            ?: SafTreeLister.listChildren(context, tree, parentId)
                .firstOrNull { !it.isDirectory && it.name.equals(name, true) }
                ?.uri
            ?: if (name.equals("index.html", true) || name.equals("index.htm", true)) {
                SafTreeLister.findIndexHtmlByGuess(context, tree, parentId)
            } else {
                null
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
        if (uri != null) {
            return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        // DocumentsContract miss (shell create lag): primary File fallback.
        val file = SafPrimaryPath.tryResolveExisting(tree, n)
            ?: error("Missing $relativePath")
        if (!file.isFile) error("Missing $relativePath")
        return file.readBytes()
    }

    override fun delete(rootPath: String, relativePath: String, isFolder: Boolean) {
        val tree = treeUri(rootPath)
        val n = relativePath.replace('\\', '/').trim('/')
        if (n.isEmpty()) error("Cannot delete tree root")

        val found = find(rootPath, n)
        if (found != null) {
            val docId = runCatching { DocumentsContract.getDocumentId(found.uri) }.getOrNull()
                ?: NotesTreeBuilder.folderDocumentId(tree, n)
            if (isFolder) {
                deleteDocumentRecursive(tree, docId)
            } else {
                if (!DocumentsContract.deleteDocument(context.contentResolver, found.uri)) {
                    found.delete()
                }
            }
            return
        }
        // File-only child (SAF listing lag): delete via java.io.File.
        val file = SafPrimaryPath.tryResolveExisting(tree, n) ?: return
        if (isFolder) file.deleteRecursively() else file.delete()
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
            ?: return
        // Never DocumentFile.listFiles — SingleDocumentFile throws UnsupportedOperationException.
        val children = SafTreeLister.listChildren(context, tree, fromId)
            .map { it.name to (it.isDirectory to it.uri) }
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
        val listedNames = children.map { it.first.lowercase() }.toSet()
        if ("index.html" !in listedNames && "index.htm" !in listedNames) {
            SafTreeLister.findIndexHtmlByGuess(context, tree, fromId)?.let { indexUri ->
                val bytes = context.contentResolver.openInputStream(indexUri)!!.use { it.readBytes() }
                writeFile(rootPath, PathUtil.join(toRel, "index.html"), bytes)
            }
        }
    }

    override fun exists(rootPath: String, relativePath: String): Boolean =
        runCatching {
            if (find(rootPath, relativePath) != null) return@runCatching true
            val tree = treeUri(rootPath)
            val n = relativePath.replace('\\', '/').trim('/')
            if (SafPrimaryPath.tryResolveExisting(tree, n) != null) return@runCatching true
            val uri = SafTreeLister.documentUri(tree, NotesTreeBuilder.folderDocumentId(tree, n))
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)

    override fun fileLengthBytes(rootPath: String, relativePath: String): Long? =
        runCatching {
            val n = relativePath.replace('\\', '/').trim('/')
            val found = find(rootPath, n)
            if (found != null) {
                if (found.isDirectory) return@runCatching null
                return@runCatching uriByteLength(found.uri)
            }
            val tree = treeUri(rootPath)
            val file = SafPrimaryPath.tryResolveExisting(tree, n) ?: return@runCatching null
            if (!file.isFile) return@runCatching null
            file.length()
        }.getOrNull()

    private fun mimeForName(name: String): String = when {
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".txt", true) -> "text/plain"
        else -> "application/octet-stream"
    }
}

