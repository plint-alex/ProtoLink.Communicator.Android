package ru.protolink.communicator.util

/**
 * Pure helpers for Share sheet Add people (share-sheet-ux §4).
 * Parity with Windows ShareAddPeople. Contacts = Messenger UserContacts;
 * paste resolve uses Auth GetUser / GetUserById (display name never GUID-shaped).
 */
object ShareAddPeople {
    private val guidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val guidInPathRegex = Regex(
        "/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b"
    )

    enum class PasteLookupKind {
        Empty,
        ById,
        ByLogin
    }

    data class PasteLookup(val kind: PasteLookupKind, val value: String)

    /** Parse raw GUID or a pasted /{userId} (or site URL ending in GUID). */
    fun parseUserIdInput(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (guidRegex.matches(s)) return s.lowercase()
        val m = guidInPathRegex.find(s) ?: return null
        return m.groupValues[1].lowercase()
    }

    /**
     * Share Add paste: GUID / URL → ById; else non-empty → ByLogin (email).
     * Mirrors Windows ShareAddPeople.ClassifyPasteLookup.
     */
    fun classifyPasteLookup(raw: String?): PasteLookup {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return PasteLookup(PasteLookupKind.Empty, "")
        val id = parseUserIdInput(s)
        if (id != null) return PasteLookup(PasteLookupKind.ById, id)
        return PasteLookup(PasteLookupKind.ByLogin, s)
    }

    /** True when paste has something to resolve (GUID, /{id} URL, or email/login). */
    fun shouldAutoResolvePaste(raw: String?): Boolean =
        classifyPasteLookup(raw).kind != PasteLookupKind.Empty

    /** True when the string is only a GUID — must never be shown as a person name. */
    fun isGuidText(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        return s.isNotEmpty() && guidRegex.matches(s)
    }

    /**
     * Pick a display name from GetUser/GetEntity fields. Returns null when missing or GUID-shaped.
     */
    fun pickResolvedDisplayName(name: String?, code: String?): String? {
        for (candidate in listOf(name, code)) {
            val t = candidate?.trim().orEmpty()
            if (t.isEmpty() || isGuidText(t)) continue
            return t
        }
        return null
    }

    /**
     * Contact entity → partner user id (`userid:…` value or Code GUID).
     * @param code entity Code
     * @param stringValues string payloads from entity values
     */
    fun extractContactUserId(code: String?, stringValues: List<String>?): String? {
        for (s in stringValues.orEmpty()) {
            if (!s.startsWith("userid:", ignoreCase = true)) continue
            val uid = s.substringAfter(":").trim()
            if (guidRegex.matches(uid)) return uid.lowercase()
        }
        val c = code?.trim().orEmpty()
        if (guidRegex.matches(c)) return c.lowercase()
        return null
    }

    /** Client-side filter over Messenger contacts (no search API). */
    fun <T> filterContacts(
        contacts: List<T>,
        nameSelector: (T) -> String,
        query: String?
    ): List<T> {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return contacts
        return contacts.filter { nameSelector(it).contains(q, ignoreCase = true) }
    }

    /** People list never includes guest_link principals (UX-S8). */
    fun isPeopleListPrincipal(principalKind: String?): Boolean =
        !principalKind.equals("guest_link", ignoreCase = true)

    fun hasAddTargets(
        selectedContactIds: Collection<String>,
        pasteResolvedUserId: String?,
        pastePreviewName: String?
    ): Boolean {
        if (selectedContactIds.isNotEmpty()) return true
        return !pasteResolvedUserId.isNullOrBlank() && !pastePreviewName.isNullOrBlank()
    }
}
