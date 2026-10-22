package ru.protolink.communicator.util

/**
 * Client-only Cloud hub nodes (AT-S17): My Cloud + Shared with me as siblings.
 * Ids are fixed and never sent to the API as parent/entity ids.
 */
object CloudVirtualNodes {
    const val HUB_ID = "a1111111-1111-4111-8111-111111111111"
    const val SHARED_WITH_ME_ID = "a2222222-2222-4222-8222-222222222222"

    fun isVirtual(id: String): Boolean =
        id.equals(HUB_ID, ignoreCase = true) ||
            id.equals(SHARED_WITH_ME_ID, ignoreCase = true)
}
