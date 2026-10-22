package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.ShareGrantDto

/**
 * Pure Notes-tree Share helpers (notes-tree-share AC-NS / AT-S47…S50).
 * External-share = people beyond creator and/or live guest links and/or public grant.
 * Parity with Windows NotesTreeShare.
 */
object NotesTreeShare {
    /**
     * True when the entity has sharing beyond “Only you” (creator-only, no live guest links).
     */
    fun hasExternalShare(
        grants: List<ShareGrantDto>?,
        liveGuestLinkCount: Int,
        currentUserId: String?
    ): Boolean {
        if (liveGuestLinkCount > 0) return true
        if (grants.isNullOrEmpty()) return false

        var otherUserCount = 0
        val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        for (g in grants) {
            if (!ShareAddPeople.isPeopleListPrincipal(g.principalKind)) continue
            val kind = g.principalKind.orEmpty()
            if (kind.equals("public", ignoreCase = true) ||
                kind.equals("group", ignoreCase = true)
            ) {
                return true
            }
            if (!kind.equals("user", ignoreCase = true)) continue

            val pid = g.permissionForId?.trim()?.lowercase().orEmpty()
            if (me != null) {
                if (pid.isNotEmpty() && pid != me) return true
            } else {
                otherUserCount++
            }
        }

        // Without knowing current user: 2+ user grants ⇒ shared; single user ⇒ Only you.
        return me == null && otherUserCount > 1
    }
}
