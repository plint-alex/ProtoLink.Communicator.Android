package ru.protolink.communicator.sync.engine

import ru.protolink.communicator.sync.ports.RemoteCreateItem

/**
 * Offline-testable helpers for Sync ApplyLocal parent+children via AddEntities.
 * Single-entity creates stay on addFolder/addFile ([shouldUseBatch]).
 */
object CloudBatchCreate {
    /** Batch when planting ≥2 new entities. One-offs stay on addFolder/addFile. */
    fun shouldUseBatch(createCount: Int): Boolean = createCount >= 2

    /**
     * Parents-before-children: every parentId that is a tempId of this batch must
     * refer to an earlier item.
     */
    fun parentsBeforeChildren(items: List<RemoteCreateItem>): Boolean {
        val pending = items.map { it.tempId }.toHashSet()
        val unresolved = pending.toMutableSet()
        for (item in items) {
            if (item.parentId in unresolved) return false
            unresolved.remove(item.tempId)
        }
        return true
    }
}
