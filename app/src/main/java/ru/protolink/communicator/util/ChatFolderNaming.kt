package ru.protolink.communicator.util

/**
 * Chat folder display names per messenger-media client-contract §4.
 * Preferred = contact display name; on collision append short contact user id.
 */
object ChatFolderNaming {
    const val CHATS_FOLDER_NAME = "Chats"

    fun normalizeDisplayName(contactDisplayName: String?): String =
        contactDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Chat"

    fun shortId(contactUserId: String): String {
        val compact = contactUserId.replace("-", "").lowercase()
        return if (compact.length >= 8) compact.substring(0, 8) else compact.padEnd(8, '0')
    }

    fun suffixedName(preferred: String, contactUserId: String): String =
        "$preferred (${shortId(contactUserId)})"

    /**
     * Name to match or create under CloudRoot/Chats.
     * If preferred is free, use it; if taken (or suffixed already exists), use the suffixed form.
     */
    fun chooseFolderName(
        contactDisplayName: String?,
        contactUserId: String,
        existingSiblingNames: Collection<String>
    ): String {
        val preferred = normalizeDisplayName(contactDisplayName)
        val suffixed = suffixedName(preferred, contactUserId)
        val set = existingSiblingNames
            .mapNotNull { it.trim().takeIf { n -> n.isNotEmpty() } }
            .map { it.lowercase() }
            .toHashSet()

        if (set.contains(suffixed.lowercase())) return suffixed
        if (set.contains(preferred.lowercase())) return suffixed
        return preferred
    }

    /** Two contacts with the same display name must get two distinct folder names. */
    fun distinctNamesForCollision(
        sharedDisplayName: String,
        firstContactId: String,
        secondContactId: String
    ): Pair<String, String> {
        val names = mutableListOf<String>()
        val first = chooseFolderName(sharedDisplayName, firstContactId, names)
        names.add(first)
        val second = chooseFolderName(sharedDisplayName, secondContactId, names)
        return first to second
    }
}
