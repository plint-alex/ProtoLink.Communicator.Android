package ru.protolink.communicator.util

/**
 * Dual same-named CloudFolders (e.g. two "MyNotesFolder") break cross-device sync when
 * Create-new in the map wizard adds a second id while Win already maps the populated one.
 *
 * Canonical pick among case-insensitive name matches:
 * 1) higher [CloudInteractiveListing.healRichnessScore] (folder×10 − files — junk
 *    CloudFiles must not demote folder-rich 898c1453 to near-clone twin 0bc549dd),
 * 2) then earliest creationTime,
 * 3) then id.
 */
object SameNamedCloudFolderHeal {

    data class Sibling(
        val id: String,
        val name: String,
        val creationTime: String? = null,
        /** CloudFolder/CloudRoot immediate children only. */
        val immediateChildCount: Int = 0,
        /** CloudFile immediate children — near-clone junk demotion. */
        val immediateFileCount: Int = 0
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

    /** Prefer existing same-named sibling instead of AddEntity(create). Populated, then oldest. */
    fun preferExistingSameName(desiredName: String, siblings: List<Sibling>): Sibling? {
        val name = desiredName.trim()
        if (name.isEmpty()) return null
        val matches = siblings.filter { it.name.equals(name, ignoreCase = true) }
        if (matches.isEmpty()) return null
        return matches.minWithOrNull(siblingCanonicalOrder)
    }

    /**
     * Among siblings sharing [mappedName], return the canonical id (populated, then oldest).
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
     * remap localPath onto the canonical (populated, then oldest) id.
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

    /**
     * Win [CloudMappedSyncCoordinator.ResolveMappingAfterHeal]: after same-named heal
     * persists, Force must use the mapping for the requested local path (canonical id),
     * not the pre-heal twin CloudFolderId the UI still held.
     */
    fun resolveMappingAfterHeal(
        requestedId: String,
        requestedLocalPath: String,
        mappings: List<MappingRow>
    ): MappingRow? {
        val local = requestedLocalPath.trim()
        if (local.isNotEmpty()) {
            val byLocal = mappings.firstOrNull {
                it.localPath.trim().equals(local, ignoreCase = true)
            }
            if (byLocal != null) return byLocal
        }
        val byId = mappings.firstOrNull {
            it.cloudFolderId.equals(requestedId, ignoreCase = true)
        }
        if (byId != null) return byId
        if (requestedId.isBlank()) return null
        return MappingRow(requestedId, requestedLocalPath, "")
    }

    /** When duplicate names exist in a pick list, show short id so the user can tell them apart. */
    fun displayLabel(name: String, id: String, allNames: Collection<String>): String {
        val n = name.ifBlank { id.take(8) }
        val dup = allNames.count { it.equals(name, ignoreCase = true) } > 1
        return if (dup && id.isNotBlank()) "$n · ${id.take(8)}" else n
    }

    /** Dedupe siblings by id (case-insensitive); keep richer childCount / name / creationTime. */
    fun mergeById(vararg lists: List<Sibling>): List<Sibling> {
        val map = linkedMapOf<String, Sibling>()
        for (list in lists) {
            for (s in list) {
                if (s.id.isBlank()) continue
                val key = s.id.lowercase()
                val prev = map[key]
                if (prev == null) {
                    map[key] = s
                    continue
                }
                map[key] = prev.copy(
                    name = prev.name.ifBlank { s.name },
                    creationTime = prev.creationTime?.takeIf { it.isNotBlank() } ?: s.creationTime,
                    immediateChildCount = maxOf(prev.immediateChildCount, s.immediateChildCount),
                    immediateFileCount = maxOf(prev.immediateFileCount, s.immediateFileCount)
                )
            }
        }
        return map.values.toList()
    }

    /**
     * Pick CloudRoot ids to scan for cross-root same-named duplicates.
     * Always includes [mustIncludeIds], then "Drive", then oldest roots — capped by [maxRoots].
     *
     * Live FAIL class: Android MyNotesFolder under CloudRoot A while Win/AC populated id
     * lives under a different CloudRoot (Drive); same-parent discover alone never remaps.
     */
    fun cloudRootIdsForDiscovery(
        cloudRoots: List<Sibling>,
        mustIncludeIds: Collection<String>,
        maxRoots: Int = 16
    ): List<String> {
        if (maxRoots <= 0) return emptyList()
        val out = linkedMapOf<String, String>()
        fun add(id: String?) {
            val trimmed = id?.trim().orEmpty()
            if (trimmed.isEmpty()) return
            out.putIfAbsent(trimmed.lowercase(), trimmed)
        }
        for (id in mustIncludeIds) add(id)
        for (r in cloudRoots) {
            if (r.name.equals("Drive", ignoreCase = true)) add(r.id)
        }
        val oldestFirst = cloudRoots.sortedWith(
            compareBy<Sibling> { r ->
                r.creationTime?.trim()?.takeIf { it.isNotEmpty() } ?: "\uFFFF"
            }.thenBy { it.id.lowercase() }
        )
        for (r in oldestFirst) {
            if (out.size >= maxRoots) break
            add(r.id)
        }
        return out.values.take(maxRoots)
    }

    /**
     * FORCE_HEAL_DISCOVER_MISS_DRIVE_898: after CloudRoot child paging, which mapped
     * names still have fewer than [minHits] siblings (canonical Drive id never listed).
     */
    fun namesNeedingMoreHits(
        siblings: List<Sibling>,
        onlyNamesLower: Set<String>,
        minHits: Int = 2
    ): Set<String> {
        if (onlyNamesLower.isEmpty() || minHits < 1) return emptySet()
        return onlyNamesLower.filterTo(linkedSetOf()) { n ->
            siblings.count { it.name.trim().equals(n, ignoreCase = true) } < minHits
        }
    }

    /**
     * SqlServer SearchEntities TitlePrefix/MidContains are collation-sensitive in some
     * environments — lowercased mapped keys (`mynotesfolder`) can yield raw hits=0 while
     * display casing (`MyNotesFolder`) matches. Prefer sibling / mapping display titles.
     */
    fun exactTitleSearchQueries(
        needLower: String,
        siblings: List<Sibling>,
        mappingDisplayByLower: Map<String, String> = emptyMap()
    ): List<String> {
        val key = needLower.trim().lowercase()
        if (key.length < 2) return emptyList()
        val out = linkedSetOf<String>()
        fun add(raw: String?) {
            val t = raw?.trim().orEmpty()
            if (t.length >= 2) out.add(t)
        }
        add(siblings.firstOrNull { it.name.trim().equals(key, ignoreCase = true) }?.name)
        add(mappingDisplayByLower[key])
        add(needLower.trim())
        // Last resort: capitalize first letter (MyNotesFolder-style keys already covered above).
        if (key.isNotEmpty()) {
            add(key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
        }
        return out.toList()
    }

    /** Lightweight SearchEntities row for unit-tested exact-title / typeTag filtering. */
    data class SearchTitleHit(
        val id: String?,
        val title: String?,
        val code: String? = null,
        val typeTag: String? = null,
        val updateTime: String? = null
    )

    /**
     * KEEP exact-title CloudFolder/CloudRoot rows for [query].
     * FAIL classes: API empty → []; typeTag/code non-folder → drop; case via equalsIgnoreCase.
     */
    fun filterExactTitleFolderHits(
        query: String,
        hits: List<SearchTitleHit>,
        folderCode: String = "CloudFolder",
        rootCode: String = "CloudRoot"
    ): List<Sibling> {
        val q = query.trim()
        if (q.length < 2 || hits.isEmpty()) return emptyList()
        val out = ArrayList<Sibling>()
        for (hit in hits) {
            val id = hit.id?.trim().orEmpty()
            if (id.isEmpty()) continue
            val title = hit.title?.trim().orEmpty()
            if (!title.equals(q, ignoreCase = true)) continue
            val code = hit.code?.trim().orEmpty()
            val tag = hit.typeTag?.trim().orEmpty()
            val isFolder =
                code.equals(folderCode, ignoreCase = true) ||
                    code.equals(rootCode, ignoreCase = true) ||
                    tag.equals(folderCode, ignoreCase = true) ||
                    tag.equals(rootCode, ignoreCase = true) ||
                    (code.isEmpty() && tag.isEmpty())
            if (!isFolder) continue
            out.add(Sibling(id = id, name = title, creationTime = hit.updateTime))
        }
        return out
    }

    /**
     * Merge exact-name SearchEntities CloudFolder hits into root-paged siblings so
     * prefer-populated remap can see Drive 898c1453 when files-first child paging
     * never reached the folder under a polluted CloudRoot.
     */
    fun mergeExactNameSearchHits(
        siblings: List<Sibling>,
        searchHits: List<Sibling>,
        onlyNamesLower: Set<String>
    ): List<Sibling> {
        if (searchHits.isEmpty() || onlyNamesLower.isEmpty()) return siblings
        val relevant = searchHits.filter { s ->
            val n = s.name.trim().lowercase()
            n.isNotEmpty() && n in onlyNamesLower
        }
        if (relevant.isEmpty()) return siblings
        return mergeById(siblings, relevant)
    }

    private val siblingCanonicalOrder = Comparator<Sibling> { a, b ->
        val scoreCmp = CloudInteractiveListing.healRichnessScore(
            b.immediateChildCount,
            b.immediateFileCount
        ).compareTo(
            CloudInteractiveListing.healRichnessScore(
                a.immediateChildCount,
                a.immediateFileCount
            )
        )
        if (scoreCmp != 0) return@Comparator scoreCmp
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
