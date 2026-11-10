package ru.protolink.communicator.util

/**
 * Pure helpers for Share sheet → Messenger send (cycle1-next-polish UX-S-AM*).
 * No Api; shell opens contact picker and prefills composer with the URL.
 */
object ShareSendInMessenger {
    fun normalizeUrl(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() }

    fun canonicalUrl(publicSiteBaseUrl: String?, entityId: String?): String? {
        val id = entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ShareSheetLogic.buildCanonicalLink(publicSiteBaseUrl, id)
    }

    /** Offer the action when there is a URL and at least one Messenger contact. */
    fun canOffer(url: String?, contactCount: Int): Boolean =
        normalizeUrl(url) != null && contactCount > 0
}
