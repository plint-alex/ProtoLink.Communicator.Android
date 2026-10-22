package ru.protolink.communicator.util

/**
 * Shared messenger string-value parse (client-contract §1–2).
 * Sole place that matches the `attachment:` prefix — migration surface for FileValue later.
 */
object MessageStringValues {
    const val ATTACHMENT_PREFIX = "attachment:"

    data class Parsed(
        val bodyText: String,
        val attachmentIds: List<String>
    )

    /**
     * Parse message string values into body text and ordered attachment entity ids.
     * Skip list (case-sensitive, prefix-only): sender:, receiver:, status:, attachment:.
     * Malformed `attachment:` values (non-GUID) are skipped, never treated as body text.
     */
    fun parse(stringValues: Iterable<String?>): Parsed {
        val attachmentIds = mutableListOf<String>()
        var body: String? = null

        for (raw in stringValues) {
            if (raw.isNullOrEmpty()) continue

            if (raw.startsWith("sender:") ||
                raw.startsWith("receiver:") ||
                raw.startsWith("status:")
            ) {
                continue
            }

            if (raw.startsWith(ATTACHMENT_PREFIX)) {
                val idPart = raw.substring(ATTACHMENT_PREFIX.length)
                if (isGuidD(idPart)) {
                    attachmentIds.add(idPart.lowercase())
                }
                continue
            }

            // One body string is normal; last non-metadata wins (parity with Windows).
            body = raw
        }

        return Parsed(bodyText = body.orEmpty(), attachmentIds = attachmentIds)
    }

    /** Guid "D" format: 8-4-4-4-12 hex with hyphens. */
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
