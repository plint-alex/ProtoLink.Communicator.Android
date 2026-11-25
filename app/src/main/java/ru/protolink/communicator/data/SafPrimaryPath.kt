package ru.protolink.communicator.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * Resolve primary-external SAF tree URIs to java.io.File paths.
 *
 * DocumentsContract children queries can lag behind shell/MediaProvider creates under the same
 * tree (tester adb mkdir fixtures). Sync snapshot/read must still see those paths so ApplyLocal
 * can upload (Win File.Exists parity on the mapped root).
 *
 * Under targetSdk 34 without MANAGE_EXTERNAL_STORAGE, [File.listFiles] on Documents/ often returns
 * null even when the path exists — prefer [listChildNames] (Os.listdir) + [flushListing] before SAF
 * re-query. This is a SAF FS peculiarity only — not a second SyncEngine path.
 */
object SafPrimaryPath {
    private const val TAG = "ProtoLinkSaf"
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_PREFIX = "primary:"
    /** Default wait after MediaScanner for root flush (adb mkdir → DocumentsContract). */
    const val FLUSH_WAIT_ROOT_MS = 1_500L
    /** Nested merge: Os.listdir already sees shell creates — do not block 1.5s×dirs (Force starve). */
    const val FLUSH_WAIT_NESTED_MS = 0L

    /** Absolute File for [treeUri] + optional relative path, or null when not a primary tree. */
    fun tryResolve(treeUri: Uri, relativePath: String = ""): File? {
        if (treeUri.authority != EXTERNAL_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        if (!docId.startsWith(PRIMARY_PREFIX)) return null
        val underPrimary = docId.removePrefix(PRIMARY_PREFIX).trim('/')
        val base = if (underPrimary.isEmpty()) {
            File("/storage/emulated/0")
        } else {
            File("/storage/emulated/0", underPrimary)
        }
        val rel = relativePath.replace('\\', '/').trim('/')
        val target = if (rel.isEmpty()) base else File(base, rel)
        return target
    }

    fun tryResolveExisting(treeUri: Uri, relativePath: String = ""): File? =
        tryResolve(treeUri, relativePath)?.takeIf { it.exists() }

    /**
     * Win GetDirectories/GetFiles decision for one Os.listdir name under a primary root.
     * Real directory or non-empty laggy dir → Directory; readable file → File; else Omit
     * (no ghost folder stubs — those blocked [LocalChange.Removed] after shell rm on 1.0.111).
     * Do not open inputStream (OEM hang risk on unlistable names → sticky Syncing).
     */
    enum class PrimaryChildKind { Directory, File, Omit }

    fun classifyPrimaryChild(child: File): PrimaryChildKind {
        if (child.isDirectory) return PrimaryChildKind.Directory
        if (child.isFile) return PrimaryChildKind.File
        if (!child.exists()) return PrimaryChildKind.Omit
        // Laggy directory metadata with visible kids (DocumentsContract mkdir race).
        val kids = listChildNames(child).filter { it != "." && it != ".." }
        if (kids.isNotEmpty()) return PrimaryChildKind.Directory
        return PrimaryChildKind.Omit
    }

    /**
     * Child names under a primary directory. Uses [File.list] first; on scoped-storage null,
     * [Files.list] then reflective `android.system.Os.listdir` so adb mkdir fixtures still
     * enter the SAF File merge (Win File.Exists discovery parity).
     */
    fun listChildNames(dir: File): List<String> {
        dir.list()?.let { return it.toList() }
        val nio = runCatching {
            Files.list(dir.toPath()).use { stream ->
                stream.map { it.fileName.toString() }.collect(Collectors.toList())
            }
        }.getOrElse { e ->
            Log.d(TAG, "Files.list miss ${dir.absolutePath}: ${e.message}")
            null
        }
        if (nio != null) return nio
        return runCatching {
            val os = Class.forName("android.system.Os")
            val method = os.getMethod("listdir", String::class.java)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null, dir.absolutePath) as? Array<String>)?.toList().orEmpty()
        }.getOrElse { e ->
            Log.d(TAG, "Os.listdir miss ${dir.absolutePath}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Nudge MediaProvider + DocumentsContract so ExternalStorageProvider re-lists shell creates.
     * Best-effort; failures must not abort Sync.
     *
     * @param waitMs max wait for MediaScanner callback. Root snapshot may use
     * [FLUSH_WAIT_ROOT_MS]; per-directory File merge must use [FLUSH_WAIT_NESTED_MS] (0) —
     * Win SnapshotFs has no per-dir sleep; 1.5s×folders starved Force before first Api
     * (1.0.103 FORCE_UPLOAD_STUCK_NO_PROGRESS residual).
     */
    fun flushListing(
        context: Context,
        treeUri: Uri,
        relativePath: String = "",
        waitMs: Long = FLUSH_WAIT_ROOT_MS
    ) {
        val dir = tryResolve(treeUri, relativePath) ?: return
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rel = relativePath.replace('\\', '/').trim('/')
            val parentId = if (rel.isEmpty()) rootId else "$rootId/$rel"
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            context.contentResolver.notifyChange(childrenUri, null)
        }.onFailure { e ->
            Log.d(TAG, "notifyChange soft-fail: ${e.message}")
        }
        if (!dir.exists()) return
        if (waitMs <= 0L) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(dir.absolutePath),
                    null,
                    null
                )
            }
            return
        }
        val latch = CountDownLatch(1)
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dir.absolutePath),
                null
            ) { _, _ -> latch.countDown() }
        }.onFailure {
            latch.countDown()
        }
        runCatching { latch.await(waitMs, TimeUnit.MILLISECONDS) }
    }
}
