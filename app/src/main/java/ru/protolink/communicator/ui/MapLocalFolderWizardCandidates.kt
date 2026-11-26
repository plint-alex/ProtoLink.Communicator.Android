package ru.protolink.communicator.ui

import ru.protolink.communicator.data.CloudEntityClassify
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.util.EntityMeta
import ru.protolink.communicator.util.SameNamedCloudFolderHeal

/**
 * Pure E6 wizard pick-list shaping. Interactive listing only — never unbounded
 * SameNamed cross-root discovery (that blocked wizard open / AT-NMR-7).
 * Parity with Windows MapLocalFolderWizardCandidates.
 */
object MapLocalFolderWizardCandidates {
    /**
     * Unmapped CloudFolder rows from a parent listing page, with short-id labels when names collide
     * within this page.
     */
    fun fromParentListing(
        entities: Collection<EntityDto>?,
        mappedFolderIds: Set<String>,
        isFolderSynced: (String) -> Boolean = { false }
    ): List<MapLocalFolderCandidate> {
        val mappedLower = mappedFolderIds.map { it.lowercase() }.toSet()
        val folders = ArrayList<Pair<String, String>>()
        for (e in entities.orEmpty()) {
            if (!CloudEntityClassify.isFolder(e.typeEntityId, e.code)) continue
            if (CloudEntityClassify.isCloudRootCode(e.code)) continue
            val id = e.id.trim()
            if (id.isEmpty()) continue
            if (mappedLower.contains(id.lowercase()) || isFolderSynced(id)) continue
            folders.add(id to EntityMeta.displayName(e))
        }

        val names = folders.map { it.second }
        return folders
            .sortedWith { a, b ->
                val byName = a.second.compareTo(b.second, ignoreCase = true)
                if (byName != 0) byName else a.first.compareTo(b.first, ignoreCase = true)
            }
            .map { (id, name) ->
                MapLocalFolderCandidate(
                    id = id,
                    name = SameNamedCloudFolderHeal.displayLabel(name, id, names)
                )
            }
    }

    /**
     * Prefer an existing same-named child under the create parent (first interactive pages).
     * Cross-CloudRoot duals stay on Sync SameNamed heal — do not block wizard confirm.
     */
    fun preferSameNamedUnderParent(
        desiredName: String,
        entities: Collection<EntityDto>?
    ): Pair<String, String>? {
        val want = desiredName.trim()
        if (want.isEmpty()) return null

        for (e in entities.orEmpty()) {
            if (!CloudEntityClassify.isFolder(e.typeEntityId, e.code)) continue
            if (CloudEntityClassify.isCloudRootCode(e.code)) continue
            val name = EntityMeta.displayName(e)
            if (name.trim().equals(want, ignoreCase = true)) {
                val id = e.id.trim()
                if (id.isEmpty()) continue
                return id to name.ifBlank { want }
            }
        }
        return null
    }
}
