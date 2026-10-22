package ru.protolink.communicator.util

import ru.protolink.communicator.data.api.ShareGrantDto
import ru.protolink.communicator.data.api.ShareLinkListItemDto
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Pure Share sheet helpers (UX §§2–3). Parity with ViewEditor share-sheet-helpers + Windows.
 */
object ShareSheetLogic {
    const val MAX_LIVE_GUEST_LINKS = 20

    data class ExpiryPayload(val neverExpires: Boolean, val expiresAtIso: String?)

    data class PartitionedLinks(
        val live: List<ShareLinkListItemDto>,
        val inactive: List<ShareLinkListItemDto>
    )

    data class PersonRow(
        val grant: ShareGrantDto,
        val isCreator: Boolean
    )

    fun canMutate(isOnline: Boolean, canEdit: Boolean, showCreatedPanel: Boolean): Boolean =
        isOnline && canEdit && !showCreatedPanel

    fun showSubtreeLine(isFolder: Boolean): Boolean = isFolder

    fun showCreateButton(
        canMutate: Boolean,
        showCreateForm: Boolean,
        showCreatedPanel: Boolean,
        liveCount: Int
    ): Boolean =
        canMutate && !showCreateForm && !showCreatedPanel && liveCount < MAX_LIVE_GUEST_LINKS

    fun showLinkLimit(liveCount: Int): Boolean = liveCount >= MAX_LIVE_GUEST_LINKS

    fun isPeopleListPrincipal(principalKind: String?): Boolean =
        !principalKind.equals("guest_link", ignoreCase = true)

    /**
     * People rows for Share sheet. When [creatorUserId] is null (CloudRoot), the signed-in
     * user's own write grant is treated as the effective owner so Remove is not offered on self.
     */
    fun peopleFromGrants(
        grants: List<ShareGrantDto>?,
        creatorUserId: String?,
        currentUserId: String? = null
    ): List<PersonRow> {
        val creator = creatorUserId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val me = currentUserId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val out = ArrayList<PersonRow>()
        for (g in grants.orEmpty()) {
            if (!isPeopleListPrincipal(g.principalKind)) continue
            val forId = g.permissionForId?.trim()?.lowercase()
            val isUser = g.principalKind.equals("user", ignoreCase = true)
            val isCreator = when {
                creator != null && forId == creator && isUser -> true
                creator == null && me != null && forId == me && isUser && g.canWrite -> true
                else -> false
            }
            out.add(PersonRow(g, isCreator))
        }
        return out
    }

    fun partitionLinks(links: List<ShareLinkListItemDto>?): PartitionedLinks {
        val sorted = links.orEmpty().sortedByDescending { it.createdAt.orEmpty() }
        val live = sorted.filter { it.isLive }
        val inactive = sorted.filter { !it.isLive }
        return PartitionedLinks(live, inactive)
    }

    fun linkRowTitle(lang: String?, label: String?, tokenPrefix: String?): String {
        val prefix = tokenPrefix.orEmpty()
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            return if (prefix.isNotEmpty()) "$trimmed · …$prefix" else trimmed
        }
        return ShareSheetStrings.linkEnding(lang, prefix)
    }

    fun personDisplayName(lang: String?, grant: ShareGrantDto): String {
        if (grant.principalKind.equals("public", ignoreCase = true)) {
            return ShareSheetStrings.anyoneOnInternet(lang)
        }
        val name = grant.displayName?.trim().orEmpty()
        return name.ifEmpty { "—" }
    }

    fun expiryPayload(choiceDays: Int, now: Instant = Instant.now()): ExpiryPayload {
        if (choiceDays == 0) return ExpiryPayload(neverExpires = true, expiresAtIso = null)
        val expires = now.plus(choiceDays.toLong(), ChronoUnit.DAYS)
        return ExpiryPayload(neverExpires = false, expiresAtIso = expires.toString())
    }

    /** UX: Extend when remaining &lt; 24h (and still live / not revoked). */
    fun isExpiringSoon(expiresAtIso: String?, now: Instant = Instant.now()): Boolean {
        if (expiresAtIso.isNullOrBlank()) return false
        val exp = parseApiInstant(expiresAtIso) ?: return false
        val hours = ChronoUnit.HOURS.between(now, exp)
        return hours in 0 until 24
    }

    /**
     * Guest-link row expiry line — Windows [ShareGuestLinkItemViewModel.ExpiryLine] parity.
     * Examples: “Never expires”, “Expires in 5 hours”, “Expires 16 Nov”, “Expired …”, “Turned off …”.
     */
    fun expiryLine(
        lang: String?,
        expiresAtIso: String?,
        revokedAtIso: String? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val ru = ShareSheetCloudStrings.isRussian(lang)
        if (!revokedAtIso.isNullOrBlank()) {
            val whenMeta = listMetaFromIso(revokedAtIso, now, zone)
            return if (ru) {
                if (whenMeta.isEmpty()) "Отключена" else "Отключена $whenMeta"
            } else {
                if (whenMeta.isEmpty()) "Turned off" else "Turned off $whenMeta"
            }
        }
        if (expiresAtIso.isNullOrBlank()) {
            return if (ru) "Без срока" else "Never expires"
        }
        // Api DateTime? often omits Z (Unspecified) — Instant.parse alone fails; use ChatTime.parseMillis.
        val exp = parseApiInstant(expiresAtIso)
            ?: return if (ru) "Истекает" else "Expires"
        val remaining = Duration.between(now, exp)
        val totalHours = remaining.toMillis() / 3_600_000.0
        if (totalHours > 0 && totalHours < 24) {
            val hours = max(1, ceil(totalHours).toInt())
            return if (ru) "Истекает через $hours ч" else "Expires in $hours hours"
        }
        val whenMeta = listMetaFromIso(expiresAtIso, now, zone)
        if (remaining.seconds <= 0) {
            return if (ru) {
                if (whenMeta.isEmpty()) "Истекла" else "Истекла $whenMeta"
            } else {
                if (whenMeta.isEmpty()) "Expired" else "Expired $whenMeta"
            }
        }
        return if (ru) {
            if (whenMeta.isEmpty()) "Истекает" else "Истекает $whenMeta"
        } else {
            if (whenMeta.isEmpty()) "Expires" else "Expires $whenMeta"
        }
    }

    /** Parse Api expiresAt / revokedAt (Z or Unspecified .NET ISO) via [ChatTime.parseMillis]. */
    private fun parseApiInstant(iso: String): Instant? {
        val millis = ChatTime.parseMillis(iso)
        return if (millis > 0L) Instant.ofEpochMilli(millis) else null
    }

    private fun listMetaFromIso(iso: String, now: Instant, zone: ZoneId): String {
        val millis = ChatTime.parseMillis(iso)
        if (millis <= 0L) return ""
        return ChatTime.formatListMeta(millis, now.toEpochMilli(), zone)
    }

    fun assertNoForbiddenUserText(s: String?): Boolean {
        val lower = (s ?: "").lowercase()
        if ("token" in lower) return false
        if (Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
                .containsMatchIn(lower)
        ) {
            return false
        }
        if (Regex("""\b(401|403|404|500)\b""").containsMatchIn(s ?: "")) return false
        return true
    }

    fun buildCanonicalLink(publicSiteBaseUrl: String?, entityId: String): String =
        EntityLinkUnfurl.buildEntityUrl(publicSiteBaseUrl, entityId)
}
