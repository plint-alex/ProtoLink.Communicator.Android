package ru.protolink.communicator.syncadapt

/**
 * Live 1.0.66 FORCE_NO_PROGRESS: Force set forceRunning (Download all disabled) but status
 * stayed "No local changes" then "Full sync queued…" — local-push completion and full-sync
 * coalesce overwrote Force UI while the Force job still owned the lane. Windows
 * RequestFullSync defers without clobbering Force banner; Android must match.
 *
 * Live 1.0.73: same class via Cloud browse-during-Force — loadCloudFolder wrote "Cloud loaded"
 * while Download all stayed disabled; heal discover raced Force pull. Guard listing reload too.
 *
 * Live 1.0.74: same class via Messenger — loadContacts / enrich wrote "Contacts: N" while
 * Force still held the lane (Download all disabled, ProtoLinkSync locked,pulling…). Guard
 * contacts (and other non-Force status writers) the same way.
 */
object ForceStatusGuard {
    /** True when Force (or conflict resolve using the Force lane) owns the status line. */
    fun ownsStatus(forceRunning: Boolean, forcePreferred: Boolean): Boolean =
        forceRunning || forcePreferred

    /**
     * Non-Force paths may update [proposed] only when Force does not own the banner.
     * Returns null when the caller must leave status unchanged.
     */
    fun statusOrNull(
        forceRunning: Boolean,
        forcePreferred: Boolean,
        proposed: String
    ): String? = if (ownsStatus(forceRunning, forcePreferred)) null else proposed
}
