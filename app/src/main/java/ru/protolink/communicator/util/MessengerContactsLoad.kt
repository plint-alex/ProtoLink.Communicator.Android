package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.EntityValueDto
import java.util.concurrent.TimeUnit

/**
 * Messenger contacts-list startup contract (#messenger-startup-priority / #contacts-responsiveness).
 * Paint loading chrome immediately; prefer Interactive over Sync; never silent-empty while fetch runs.
 * Parity with Windows MessengerContactsLoad (tip 1.0.98).
 */
object MessengerContactsLoad {
    /** Leave contacts Loading within this budget from load start. */
    val InteractiveHttpTimeoutMs: Long = TimeUnit.SECONDS.toMillis(12)

    const val LoadingStatus = "Loading…"
    const val EmptyHint = "No contacts yet. Add someone in Settings."
    const val LoadFailedSoft = "Couldn't load contacts."

    fun shouldShowLoading(isLoading: Boolean): Boolean = isLoading

    /** True empty bag after a finished load — never confuse with in-flight empty. */
    fun shouldShowEmpty(isLoading: Boolean, contactCount: Int): Boolean =
        !isLoading && contactCount <= 0

    /**
     * Extract contact user id from a UserContacts child entity (values or code).
     * Oldest bags sometimes store the self row as login email with empty values —
     * pass [selfUserId]/[selfLogin] so that row still counts as a distinct person.
     */
    fun tryParseContactUserId(
        values: List<EntityValueDto>?,
        code: String?,
        selfUserId: String? = null,
        selfLogin: String? = null
    ): String? {
        if (values != null) {
            for (ev in values) {
                val s = ev.value?.asString
                if (!s.isNullOrEmpty()
                    && s.startsWith("userid:", ignoreCase = true)
                ) {
                    val uid = s.substringAfter(":").trim()
                    if (uid.isNotEmpty() && uid.length >= 32) return uid
                }
            }
        }
        val c = code?.trim().orEmpty()
        if (c.length == 36) return c
        if (!selfUserId.isNullOrBlank()
            && !selfLogin.isNullOrBlank()
            && c.equals(selfLogin, ignoreCase = true)
        ) {
            return selfUserId
        }
        return null
    }
}
