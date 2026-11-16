package ru.protolink.communicator.util

/**
 * Dual same-named CloudFolders (e.g. two "MyNotesFolder") break cross-device sync when
 * Create-new in the map wizard adds a second id while Win already maps the older one.
 * Canonical pick = earliest creationTime among case-insensitive name matches.
 */
object SameNamedCloudFolderHeal {

    data class Sibling(
        val id: String,
        val name: String,
        val creationTime: String? = null
    )

    data class MappingRow(
        val cloudFolderId: String,
        val localPath: String,
        val cloudFolderName: String
    )

    data class Remap(
        val fromId: String,
        val toId: String,
        val localPath: String,
        val folderName: String
    )

    /** Prefer existing same-named sibling instead of AddEntity(create). Oldest wins. */
    fun preferExistingSameName(desiredName: String, siblings: List<Sibling>): Sibling? {
        val name = desiredName.trim()
        if (name.isEmpty()) return null
        val matches = siblings.filter { it.name.equals(name, ignoreCase = true) }
        if (matches.isEmpty()) return null
        return matches.minWithOrNull(siblingCanonicalOrder)
    }

    /**
     * Among siblings sharing [mappedName], return the canonical id (oldest).
     * Null when there is no duplicate or mapped already is canonical.
     */
    fun canonicalIdIfMappedIsDuplicate(
        mappedId: String,
        mappedName: String,
        siblings: List<Sibling>
    ): String? {
        val name = mappedName.trim()
        if (name.isEmpty() || mappedId.isBlank()) return null
        val matches = siblings.filter { it.name.equals(name, ignoreCase = true) }
        if (matches.size < 2) return null
        val canonical = matches.minWithOrNull(siblingCanonicalOrder) ?: return null
        if (canonical.id.equals(mappedId, ignoreCase = true)) return null
        return canonical.id
    }

    /**
     * For each mapping whose Cloud folder name collides with a sibling under the same parent,
     * remap localPath onto the canonical (oldest) id.
     */
    fun remapsForDuplicateNames(
        mappings: List<MappingRow>,
        siblingsByParent: Map<String, List<Sibling>>,
        parentIdByMappedId: Map<String, String>
    ): List<Remap> {
        val out = mutableListOf<Remap>()
        val seenLocal = mutableSetOf<String>()
        for (m in mappings) {
            val parent = parentIdByMappedId.entries
                .firstOrNull { it.key.equals(m.cloudFolderId, ignoreCase = true) }
                ?.value
                ?: continue
            val siblings = siblingsByParent.entries
                .firstOrNull { it.key.equals(parent, ignoreCase = true) }
                ?.value
                ?: continue
            val name = m.cloudFolderName.ifBlank {
                siblings.firstOrNull { it.id.equals(m.cloudFolderId, ignoreCase = true) }?.name.orEmpty()
            }
            val toId = canonicalIdIfMappedIsDuplicate(m.cloudFolderId, name, siblings) ?: continue
            val localKey = m.localPath.trim().lowercase()
            if (localKey.isNotEmpty() && !seenLocal.add(localKey)) continue
            val toName = siblings.firstOrNull { it.id.equals(toId, ignoreCase = true) }?.name
                ?: name
            out.add(
                Remap(
                    fromId = m.cloudFolderId,
                    toId = toId,
                    localPath = m.localPath,
                    folderName = toName
                )
            )
        }
        return out
    }

    /** Apply remaps: drop from/to ids and any same localPath, then add canonical rows. */
    fun applyRemaps(mappings: List<MappingRow>, remaps: List<Remap>): List<MappingRow> {
        if (remaps.isEmpty()) return mappings
        val dropIds = remaps.flatMap { listOf(it.fromId, it.toId) }
            .map { it.lowercase() }
            .toSet()
        val dropLocals = remaps.map { it.localPath.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val kept = mappings.filter { m ->
            val id = m.cloudFolderId.lowercase()
            val local = m.localPath.trim().lowercase()
            id !in dropIds && (local.isEmpty() || local !in dropLocals)
        }
        val added = remaps.map {
            MappingRow(
                cloudFolderId = it.toId,
                localPath = it.localPath,
                cloudFolderName = it.folderName
            )
        }
        return kept + added
    }

    /** When duplicate names exist in a pick list, show short id so the user can tell them apart. */
    fun displayLabel(name: String, id: String, allNames: Collection<String>): String {
        val n = name.ifBlank { id.take(8) }
        val dup = allNames.count { it.equals(name, ignoreCase = true) } > 1
        return if (dup && id.isNotBlank()) "$n · ${id.take(8)}" else n
    }

    private val siblingCanonicalOrder = Comparator<Sibling> { a, b ->
        val ta = a.creationTime?.trim().orEmpty()
        val tb = b.creationTime?.trim().orEmpty()
        when {
            ta.isNotEmpty() && tb.isNotEmpty() && ta != tb -> ta.compareTo(tb)
            ta.isNotEmpty() && tb.isEmpty() -> -1
            ta.isEmpty() && tb.isNotEmpty() -> 1
            else -> a.id.compareTo(b.id, ignoreCase = true)
        }
    }
}
