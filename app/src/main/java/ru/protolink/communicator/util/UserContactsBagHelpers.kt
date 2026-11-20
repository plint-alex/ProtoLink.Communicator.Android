package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.EntityDto

/**
 * Pure helpers: pick one stable UserContacts bag under CONTACTS+owner.
 * Startup-fast path skips kid-count enrich (parity with Win UserContactsBagHelpers + EnsureBagIdStartupFast).
 */
object UserContactsBagHelpers {
    const val ListPageSize = 50

    data class BagSnapshot(
        val id: String,
        val creationTime: String? = null,
        val personRowCount: Int? = null
    )

    fun shouldCreateBag(bags: List<EntityDto>?): Boolean {
        if (bags.isNullOrEmpty()) return true
        return bags.none { it.id.isNotBlank() }
    }

    fun shouldCreateBagFromSnapshots(bags: List<BagSnapshot>?): Boolean {
        if (bags.isNullOrEmpty()) return true
        return bags.none { it.id.isNotBlank() }
    }

    fun toSnapshots(entities: List<EntityDto>?): List<BagSnapshot> {
        if (entities.isNullOrEmpty()) return emptyList()
        return entities
            .filter { it.id.isNotBlank() }
            .map { BagSnapshot(id = it.id, creationTime = it.creationTime) }
    }

    /**
     * Prefer most person rows when any count is known; else oldest creationTime; id tie-break.
     */
    fun pickBag(bags: List<BagSnapshot>?): BagSnapshot? {
        if (bags.isNullOrEmpty()) return null
        val list = bags.filter { it.id.isNotBlank() }
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val anyCount = list.any { it.personRowCount != null }
        return list
            .sortedWith(
                compareByDescending<BagSnapshot> {
                    if (anyCount) it.personRowCount ?: -1 else 0
                }
                    .thenBy { it.creationTime?.takeIf { t -> t.isNotBlank() } ?: "\uFFFF" }
                    .thenBy { it.id.lowercase() }
            )
            .first()
    }

    fun pickBagId(bags: List<BagSnapshot>?): String? = pickBag(bags)?.id
}
