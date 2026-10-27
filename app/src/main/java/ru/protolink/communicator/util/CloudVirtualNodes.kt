package ru.protolink.communicator.util

/**
 * Client-only Cloud hub nodes (AT-S17 / C6 / AT-S54): My Cloud + Shared with me +
 * Shared by me + Recent as siblings. Ids are fixed and never sent to the API as parent/entity ids.
 */
object CloudVirtualNodes {
    const val HUB_ID = "a1111111-1111-4111-8111-111111111111"
    const val SHARED_WITH_ME_ID = "a2222222-2222-4222-8222-222222222222"
    /** C6 Recent virtual hub — parity with Windows / web VIRTUAL_IDS.recent. */
    const val RECENT_ID = "a3333333-3333-4333-8333-333333333333"
    /** Shared by me outbound inventory (AT-S54 / AC-SO). */
    const val SHARED_BY_ME_ID = "a4444444-4444-4444-8444-444444444444"

    fun isVirtual(id: String): Boolean =
        id.equals(HUB_ID, ignoreCase = true) ||
            id.equals(SHARED_WITH_ME_ID, ignoreCase = true) ||
            id.equals(RECENT_ID, ignoreCase = true) ||
            id.equals(SHARED_BY_ME_ID, ignoreCase = true)
}
