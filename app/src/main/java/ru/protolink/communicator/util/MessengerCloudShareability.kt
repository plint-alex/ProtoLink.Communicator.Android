package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.EntityPermissionDto

/**
 * M3 — whether a Cloud picker row may be selected for send.
 * Parity with Windows [MessengerCloudShareability] and web messenger-cloud-shareability.cjs.
 * Sender without write may only pick when the recipient already has read (fail-closed).
 */
object MessengerCloudShareability {
    const val DisabledShareSubtitleEn = "You can't share this item"
    const val DisabledShareSubtitleRu = "Вы не можете поделиться этим элементом"
    const val PickerPageSize = 200

    fun canSelect(senderHasWrite: Boolean, recipientAlreadyHasRead: Boolean): Boolean =
        senderHasWrite || recipientAlreadyHasRead

    fun disabledShareSubtitle(lang: String?): String {
        val tag = lang?.lowercase().orEmpty()
        return if (tag.startsWith("ru")) DisabledShareSubtitleRu else DisabledShareSubtitleEn
    }

    /**
     * Derive selectability from GetPermissions rows.
     * Self-chat (me == peer) always treats recipient as already having read.
     */
    fun canSelectFromPermissionRows(
        perms: List<EntityPermissionDto>?,
        myUserId: String,
        recipientUserId: String
    ): Boolean {
        val me = normalizeGuid(myUserId)
        val peer = normalizeGuid(recipientUserId)
        if (me.isEmpty() || peer.isEmpty()) return false
        var senderWrite = false
        var recipientRead = me == peer
        for (p in perms.orEmpty()) {
            val forId = normalizeGuid(p.permissionForId)
            if (forId.isEmpty()) continue
            if (forId == me && p.canWrite) senderWrite = true
            if (forId == peer) recipientRead = true
        }
        return canSelect(senderWrite, recipientRead)
    }

    fun normalizeGuid(g: String?): String =
        (g ?: "").replace("-", "").lowercase()
}
