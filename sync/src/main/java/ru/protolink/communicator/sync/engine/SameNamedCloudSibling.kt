package ru.protolink.communicator.sync.engine

import ru.protolink.communicator.sync.model.RemoteEntry
import java.time.Instant

/**
 * Before Sync addFolder/addFile, reuse an existing CloudFile/CloudFolder sibling under the
 * same parent with the same display name (Cloud can hold same-name siblings; local FS cannot).
 * Prefer newest updateTime, then id — matches devops cleanup keep-newest for files.
 */
object SameNamedCloudSibling {

    fun preferExisting(
        parentId: String,
        displayName: String,
        isFolder: Boolean,
        siblings: List<RemoteEntry>
    ): RemoteEntry? {
        val name = displayName.trim()
        if (name.isEmpty() || parentId.isBlank()) return null

        var best: RemoteEntry? = null
        for (s in siblings) {
            if (!s.parentId.equals(parentId, ignoreCase = true)) continue
            if (s.isFolder != isFolder) continue
            val sn = s.name.trim()
            if (sn.isEmpty()) continue
            if (!sn.equals(name, ignoreCase = true)) continue
            // Skip synthetic file-{guid} labels — not a real display-name match.
            if (!isFolder && sn.equals("file-${s.id.replace("-", "")}", ignoreCase = true)) continue
            if (best == null || comparePrefer(s, best) < 0) best = s
        }
        return best
    }

    /** Newer updateTime wins; then smaller id. Returns &lt;0 when a is preferred over b. */
    private fun comparePrefer(a: RemoteEntry, b: RemoteEntry): Int {
        val aTime = a.updateTime ?: Instant.MIN
        val bTime = b.updateTime ?: Instant.MIN
        val byTime = bTime.compareTo(aTime) // newer first
        if (byTime != 0) return byTime
        return a.id.compareTo(b.id, ignoreCase = true)
    }
}
