package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes

/**
 * Win-parity multi-CloudRoot pick (Windows Navigation.CloudRootSelection).
 *
 * Live FAIL ANDROID_SOFTREADY_EMPTY_LISTING: under-user GetEntities take=200 crowns an
 * empty junk CloudRoot on page 1 while healthy AT root (kids present) sits on page 2+.
 * Callers must page under-user fully, then pick here — never firstOrNull on a single page.
 */
object CloudRootSelection {
    data class Candidate(
        val id: String,
        val creationTime: String? = null
    )

    /**
     * Prefer a CloudRoot that is not a sync-mapping target; then earliest creationTime; then id.
     * When [preferredId] is among the preferred pool, keep it (LastOpen / prior My Cloud —
     * required when mappings are empty so earliest CreationTime does not crown a bloated Drive).
     */
    fun pickCanonical(
        roots: List<Candidate>,
        mappedCloudFolderIds: Set<String> = emptySet(),
        preferredId: String? = null
    ): String? {
        val pool = preferredPool(roots, mappedCloudFolderIds) ?: return null
        val pref = preferredId?.trim().orEmpty()
        if (pref.isNotEmpty() && pool.any { it.id.equals(pref, ignoreCase = true) }) {
            return pool.first { it.id.equals(pref, ignoreCase = true) }.id
        }
        return pool
            .sortedWith(earliestCreationThenId)
            .first()
            .id
    }

    /**
     * Same pool as [pickCanonical], but when preferred is absent (or not in pool) prefer the
     * root with the most CloudFolder/CloudRoot children so an empty first-page twin cannot
     * become My Cloud while Api kids live under another CloudRoot.
     */
    fun pickCanonicalPreferringRichness(
        roots: List<Candidate>,
        mappedCloudFolderIds: Set<String> = emptySet(),
        preferredId: String? = null,
        folderChildCountByRemoteId: Map<String, Int> = emptyMap()
    ): String? {
        val pool = preferredPool(roots, mappedCloudFolderIds) ?: return null
        val pref = preferredId?.trim().orEmpty()
        if (pref.isNotEmpty() && pool.any { it.id.equals(pref, ignoreCase = true) }) {
            return pool.first { it.id.equals(pref, ignoreCase = true) }.id
        }
        if (folderChildCountByRemoteId.isEmpty()) {
            return pool.sortedWith(earliestCreationThenId).first().id
        }
        return pool
            .sortedWith(
                compareByDescending<Candidate> {
                    folderChildCountByRemoteId[it.id.lowercase()] ?: 0
                }
                    .thenBy { creationSortKey(it.creationTime) }
                    .thenBy { it.id.lowercase() }
            )
            .first()
            .id
    }

    /** Preferred My Cloud id: LastOpen when it is a known CloudRoot, else current in-memory. */
    fun resolvePreferredCloudRootId(
        knownCloudRootIds: Set<String>,
        lastOpenCloudEntityId: String?,
        currentMyCloudRootId: String?
    ): String? {
        if (knownCloudRootIds.isEmpty()) return null
        val known = knownCloudRootIds.map { it.lowercase() }.toHashSet()
        val last = lastOpenCloudEntityId?.trim().orEmpty()
        if (last.isNotEmpty() && known.contains(last.lowercase())) return last
        val cur = currentMyCloudRootId?.trim().orEmpty()
        if (cur.isNotEmpty() && known.contains(cur.lowercase())) return cur
        return null
    }

    /**
     * Parent CloudRoot for E6 create-then-map: among non-mapped roots prefer the newest
     * (healthy AT root tends to be newer than a legacy Drive-style duplicate).
     */
    fun pickWizardCreateParentRoot(
        roots: List<Candidate>,
        mappedCloudFolderIds: Set<String> = emptySet()
    ): String? {
        val pool = preferredPool(roots, mappedCloudFolderIds) ?: return null
        return pool
            .sortedWith(
                compareByDescending<Candidate> {
                    // Newest first; blank creation sorts last (empty string < ISO dates).
                    it.creationTime?.trim().orEmpty()
                }.thenBy { it.id.lowercase() }
            )
            .first()
            .id
    }

    fun isCloudRootCode(code: String?): Boolean =
        code.equals(CloudCodes.CLOUD_ROOT, ignoreCase = true)

    fun isKnownCloudRoot(cloudFolderId: String, knownCloudRootIds: Set<String>?): Boolean {
        if (cloudFolderId.isBlank() || knownCloudRootIds.isNullOrEmpty()) return false
        val key = cloudFolderId.lowercase()
        return knownCloudRootIds.any { it.equals(key, ignoreCase = true) }
    }

    fun shouldRefuseMapping(
        cloudEntityCode: String?,
        cloudFolderId: String,
        knownCloudRootIds: Set<String>? = null
    ): Boolean = isCloudRootCode(cloudEntityCode) || isKnownCloudRoot(cloudFolderId, knownCloudRootIds)

    fun shouldSkipLastOpenCloudRoot(
        entityCode: String?,
        entityId: String,
        canonicalCloudRootId: String?,
        cloudRootCountUnderUser: Int
    ): Boolean {
        if (cloudRootCountUnderUser <= 1) return false
        if (!isCloudRootCode(entityCode)) return false
        val canonical = canonicalCloudRootId?.trim().orEmpty()
        if (canonical.isEmpty()) return false
        return !entityId.equals(canonical, ignoreCase = true)
    }

    fun shouldFallbackToHubAfterListingFailure(
        listedFolderId: String,
        knownCloudRootIds: Set<String>?
    ): Boolean = isKnownCloudRoot(listedFolderId, knownCloudRootIds)

    private fun preferredPool(
        roots: List<Candidate>,
        mappedCloudFolderIds: Set<String>
    ): List<Candidate>? {
        val cleaned = roots.mapNotNull { c ->
            val id = c.id.trim()
            if (id.isEmpty()) null else c.copy(id = id)
        }
        if (cleaned.isEmpty()) return null
        val mapped = mappedCloudFolderIds.map { it.lowercase() }.toHashSet()
        val nonMapped = cleaned.filter { it.id.lowercase() !in mapped }
        return if (nonMapped.isNotEmpty()) nonMapped else cleaned
    }

    private val earliestCreationThenId: Comparator<Candidate> =
        compareBy<Candidate> { creationSortKey(it.creationTime) }
            .thenBy { it.id.lowercase() }

    /** Empty / blank sorts last so unknown times do not crown junk over dated roots. */
    private fun creationSortKey(creationTime: String?): String {
        val t = creationTime?.trim().orEmpty()
        return if (t.isEmpty()) "\uFFFF" else t
    }
}
