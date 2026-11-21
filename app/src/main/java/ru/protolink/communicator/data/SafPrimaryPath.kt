package ru.protolink.communicator.data

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolve primary-external SAF tree URIs to java.io.File paths.
 *
 * DocumentsContract children queries can lag behind shell/MediaProvider creates under the same
 * tree (tester adb mkdir fixtures). Sync snapshot/read must still see those paths so ApplyLocal
 * can upload (Win File.Exists parity on the mapped root).
 */
object SafPrimaryPath {
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_PREFIX = "primary:"

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
}
