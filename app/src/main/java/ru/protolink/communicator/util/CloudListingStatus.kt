package ru.protolink.communicator.util

/**
 * Footer/status after a Cloud folder listing reload.
 * Leave Shared with me (UX-LS-3) must keep §7b success instead of the default "Cloud loaded".
 */
object CloudListingStatus {
    const val DEFAULT_LOADED = "Cloud loaded"

    fun afterReload(preferredStatus: String?): String =
        preferredStatus?.takeIf { it.isNotBlank() } ?: DEFAULT_LOADED
}
