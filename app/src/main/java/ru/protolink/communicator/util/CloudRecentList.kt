package ru.protolink.communicator.util

/**
 * Pure MRU helpers for Cloud Recent (C6): dedupe by entity id, newest first, capped.
 * Parity with Windows CloudRecentList / web cloud-recent-helpers.
 */
object CloudRecentList {
    const val DEFAULT_CAPACITY = 20

    data class Entry(
        val entityId: String,
        val name: String,
        val isFolder: Boolean,
        val openedUtcMillis: Long
    )

    /**
     * Prepend [entry] (or move existing id to front with refreshed fields), then cap.
     * Virtual / blank ids are ignored.
     */
    fun push(
        current: List<Entry>,
        entry: Entry,
        capacity: Int = DEFAULT_CAPACITY
    ): List<Entry> {
        val cap = if (capacity < 1) DEFAULT_CAPACITY else capacity
        val id = entry.entityId.trim()
        if (id.isEmpty() || CloudVirtualNodes.isVirtual(id)) return current

        val name = entry.name.trim().ifEmpty { id }
        val next = Entry(id, name, entry.isFolder, entry.openedUtcMillis)
        val result = ArrayList<Entry>(cap + 1)
        result.add(next)
        for (existing in current) {
            if (existing.entityId.equals(next.entityId, ignoreCase = true)) continue
            result.add(existing)
            if (result.size >= cap) break
        }
        return result
    }

    fun remove(current: List<Entry>, entityId: String): List<Entry> {
        val id = entityId.trim()
        if (id.isEmpty()) return current
        val result = current.filterNot { it.entityId.equals(id, ignoreCase = true) }
        return if (result.size == current.size) current else result
    }

    /** Keep only entries whose ids are in [keepIds] (preserve MRU order). */
    fun keepOnly(current: List<Entry>, keepIds: Set<String>): List<Entry> {
        val lower = keepIds.map { it.lowercase() }.toSet()
        val result = current.filter { lower.contains(it.entityId.lowercase()) }
        return if (result.size == current.size) current else result
    }

    fun shouldRecordOpen(entityId: String, cloudRootId: String?): Boolean {
        val id = entityId.trim()
        if (id.isEmpty() || CloudVirtualNodes.isVirtual(id)) return false
        if (cloudRootId != null && id.equals(cloudRootId, ignoreCase = true)) return false
        return true
    }
}
