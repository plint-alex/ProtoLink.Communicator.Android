package ru.protolink.communicator.util

/**
 * Shared by me row subtitle (AC-SO-2): people preview + guest count + public chip.
 * Pure — unit-tested; no AddParent / no second revoke UI.
 */
object SharedByMeSummary {
    fun rowSubtitle(
        lang: String?,
        peopleNames: List<String>,
        peopleMoreCount: Int,
        liveGuestLinkCount: Int,
        hasPublicAccess: Boolean
    ): String {
        val parts = mutableListOf<String>()
        val people = formatPeople(peopleNames, peopleMoreCount)
        if (people.isNotBlank()) parts.add(people)
        val guests = formatGuests(lang, liveGuestLinkCount)
        if (guests.isNotBlank()) parts.add(guests)
        if (hasPublicAccess) parts.add(anyoneOnInternet(lang))
        return parts.joinToString(" · ")
    }

    fun formatPeople(names: List<String>, moreCount: Int): String {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        val joined = cleaned.joinToString(", ")
        val more = moreCount.coerceAtLeast(0)
        return if (more > 0) "$joined +$more more" else joined
    }

    fun formatGuests(lang: String?, count: Int): String {
        if (count < 1) return ""
        return if (ShareSheetCloudStrings.isRussian(lang)) {
            if (count == 1) "1 гостевая ссылка" else "$count гостевых ссылок"
        } else {
            if (count == 1) "1 guest link" else "$count guest links"
        }
    }

    fun anyoneOnInternet(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Все в интернете" else "Anyone on the internet"
}
