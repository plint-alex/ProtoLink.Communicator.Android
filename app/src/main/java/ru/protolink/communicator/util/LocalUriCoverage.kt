package ru.protolink.communicator.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Path/URI containment for Notes root ↔ Cloud mapping local roots (E6 / AT-MAP-4).
 * Android mappings store SAF tree URIs; Windows uses filesystem paths — both normalize here.
 * Pure string parsing (no android.net.Uri) so JVM unit tests stay reliable.
 */
object LocalUriCoverage {
    fun tryNormalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.contains("://", ignoreCase = true)) trimmed else trimmed.trimEnd('\\', '/').lowercase()
    }

    /**
     * True when [folderRaw] equals or is nested under [rootRaw] (file path or content tree URI).
     */
    fun isUnderOrEqual(folderRaw: String, rootRaw: String): Boolean {
        val folder = tryNormalize(folderRaw) ?: return false
        val root = tryNormalize(rootRaw) ?: return false
        if (folder.equals(root, ignoreCase = true)) return true

        if (!folder.contains("://") && !root.contains("://")) {
            if (!folder.startsWith(root, ignoreCase = true)) return false
            if (folder.length == root.length) return true
            val next = folder[root.length]
            return next == '\\' || next == '/'
        }

        return isSafDescendantOrEqual(folder, root)
    }

    /**
     * Notes root (or any folder URI/path) is “in Cloud” when it equals or lies under any mapping local root.
     */
    fun isCoveredByAnyMappingRoot(folderUriOrPath: String?, mappingLocalRoots: Iterable<String?>): Boolean {
        val folder = tryNormalize(folderUriOrPath) ?: return false
        for (rootRaw in mappingLocalRoots) {
            val root = tryNormalize(rootRaw) ?: continue
            if (isUnderOrEqual(folder, root)) return true
        }
        return false
    }

    private fun isSafDescendantOrEqual(folder: String, root: String): Boolean {
        if (folder.equals(root, ignoreCase = true)) return true
        val folderTree = decodeSegment(extractTreeDocumentId(folder) ?: return false)
        val rootTree = decodeSegment(extractTreeDocumentId(root) ?: return false)
        if (folderTree.equals(rootTree, ignoreCase = true)) return true
        if (!folderTree.startsWith(rootTree, ignoreCase = true)) return false
        if (folderTree.length == rootTree.length) return true
        val next = folderTree[rootTree.length]
        return next == '/' || next == ':'
    }

    /**
     * Document id after `/tree/` until `/document/` (keeps encoded `%2F` inside the tree id).
     */
    internal fun extractTreeDocumentId(uriString: String): String? {
        val marker = "/tree/"
        val lower = uriString.lowercase()
        val idx = lower.indexOf(marker)
        if (idx < 0) return null
        var after = uriString.substring(idx + marker.length)
        val q = after.indexOf('?')
        if (q >= 0) after = after.substring(0, q)
        val hash = after.indexOf('#')
        if (hash >= 0) after = after.substring(0, hash)
        val docMarker = "/document/"
        val docIdx = after.lowercase().indexOf(docMarker)
        if (docIdx >= 0) after = after.substring(0, docIdx)
        return after.takeIf { it.isNotBlank() }
    }

    private fun decodeSegment(encoded: String): String =
        try {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            encoded
        }
}
