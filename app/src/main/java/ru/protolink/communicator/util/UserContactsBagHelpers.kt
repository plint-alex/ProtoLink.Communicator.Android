package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.EntityDto

/**
 * Pure helpers: pick one stable UserContacts bag under CONTACTS+owner.
 * Parity with Win UserContactsBagHelpers + EnsureBagIdStartupFast escalate.
 */
object UserContactsBagHelpers {
    const val ListPageSize = 50

    /** Hard cap on pages so a broken API cannot loop forever (~25k bags). */
    const val ListMaxPages = 500

    /** Parallel dual-parent list wave size (Messenger Interactive budget). */
    const val ListPageParallelism = 4

    /** Kids page size when enriching person-row counts for pick. */
    const val KidsTake = 500

    /**
     * Partner-bearing list: self + ≥1 peer.
     * Count **distinct parseable contact user ids**, not raw kid rows — duplicate self
     * rows (kids=2, same uid) must not trip early-exit / PreferCached (UC_BAG_PICK_STARTUPFAST).
     */
    const val PartnerRowThreshold = 2

    data class BagSnapshot(
        val id: String,
        val creationTime: String? = null,
        /** Distinct parseable contact user ids under the bag (null = not enriched yet). */
        var personRowCount: Int? = null
    )

    /**
     * Distinct contact user ids among bag kids (userid: values, GUID code, or self login code).
     */
    fun countDistinctContactUserIds(
        kids: List<EntityDto>?,
        selfUserId: String? = null,
        selfLogin: String? = null
    ): Int {
        if (kids.isNullOrEmpty()) return 0
        val ids = LinkedHashSet<String>()
        for (kid in kids) {
            val uid = MessengerContactsLoad.tryParseContactUserId(
                kid.values,
                kid.code,
                selfUserId,
                selfLogin
            ) ?: continue
            ids.add(uid.lowercase())
        }
        return ids.size
    }

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
     * StartupFast may use first-page-only pick only when the newest-first page is short
     * (no hidden older siblings). A full page can hide partner bags (Tina) → escalate.
     */
    fun canPickFromStartupFastFirstPage(firstPage: List<EntityDto>?): Boolean =
        !isListPageFull(firstPage)

    fun isListPageFull(page: List<EntityDto>?, pageSize: Int = ListPageSize): Boolean {
        val take = if (pageSize > 0) pageSize else ListPageSize
        return (page?.size ?: 0) >= take
    }

    /** Merge pages by entity id (later pages do not overwrite earlier). */
    fun mergeBagPages(
        existing: List<EntityDto>?,
        page: List<EntityDto>?
    ): List<EntityDto> {
        val out = existing?.toMutableList() ?: mutableListOf()
        val seen = out.mapNotNull { it.id.takeIf { id -> id.isNotBlank() }?.lowercase() }.toMutableSet()
        if (page == null) return out
        for (bag in page) {
            val id = bag.id.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(id.lowercase())) continue
            out.add(bag)
        }
        return out
    }

    /**
     * Enrich oldest-first so partner bags (Tina) surface before thin newest spawns —
     * enables early-exit.
     */
    fun orderBagsForEnrich(bags: List<BagSnapshot>?): List<BagSnapshot> {
        if (bags.isNullOrEmpty()) return emptyList()
        return bags.sortedWith(
            compareBy<BagSnapshot> { it.creationTime?.takeIf { t -> t.isNotBlank() } ?: "\uFFFF" }
                .thenBy { it.id.lowercase() }
        )
    }

    /** True when known kid counts show at least one partner-bearing bag. */
    fun hasPartnerBearingBag(bags: List<BagSnapshot>?): Boolean {
        if (bags.isNullOrEmpty()) return false
        return bags.any { (it.personRowCount ?: 0) >= PartnerRowThreshold }
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
