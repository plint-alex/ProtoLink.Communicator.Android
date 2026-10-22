package ru.protolink.communicator.util

/**
 * M8 — detect ProtoLink entity URLs in message body text. Only `{PublicSiteBaseUrl}/{guid}`
 * (optional trailing slug / `?lang=`) is unfurled; external URLs are ignored.
 */
object EntityLinkUnfurl {
    const val DEFAULT_PUBLIC_SITE_BASE_URL = "https://protolink.ru/"

    private const val GUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    /**
     * Distinct entity ids found as ProtoLink public links under [publicSiteBaseUrl].
     * Order = first occurrence in [text].
     */
    fun findEntityIds(text: String?, publicSiteBaseUrl: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val baseUrl = normalizeBase(publicSiteBaseUrl)
        if (baseUrl.isEmpty()) return emptyList()

        val escaped = Regex.escape(baseUrl)
        val urlPattern = Regex(
            "$escaped/(?<id>$GUID_PATTERN)(?:/[^\\s?#]*)?(?:\\?[^\\s]*)?",
            RegexOption.IGNORE_CASE
        )

        val seen = linkedSetOf<String>()
        for (m in urlPattern.findAll(text)) {
            val id = m.groups["id"]?.value ?: continue
            if (isGuidD(id)) seen.add(id.lowercase())
        }
        return seen.toList()
    }

    /** True when [url] is a ProtoLink entity link for the configured base (AT-M21 external = false). */
    fun tryParseEntityUrl(url: String?, publicSiteBaseUrl: String?): String? {
        if (url.isNullOrBlank()) return null
        val baseUrl = normalizeBase(publicSiteBaseUrl)
        if (baseUrl.isEmpty()) return null

        val trimmed = url.trim()
        val prefix = "$baseUrl/"
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null

        val rest = trimmed.substring(prefix.length)
        val path = rest.substringBefore('?')
        val firstSeg = path.substringBefore('/')
        return if (isGuidD(firstSeg)) firstSeg.lowercase() else null
    }

    fun buildEntityUrl(publicSiteBaseUrl: String?, entityId: String): String {
        val base = normalizeBase(publicSiteBaseUrl)
        return "$base/${entityId.lowercase()}"
    }

    fun normalizeBase(publicSiteBaseUrl: String?): String {
        val raw = if (publicSiteBaseUrl.isNullOrBlank()) DEFAULT_PUBLIC_SITE_BASE_URL
        else publicSiteBaseUrl.trim()
        return raw.trimEnd('/')
    }

    private fun isGuidD(value: String): Boolean {
        if (value.length != 36) return false
        for (i in value.indices) {
            val c = value[i]
            when (i) {
                8, 13, 18, 23 -> if (c != '-') return false
                else -> if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
            }
        }
        return true
    }
}
