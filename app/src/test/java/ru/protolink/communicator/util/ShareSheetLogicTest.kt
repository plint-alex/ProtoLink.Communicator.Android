package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.api.ShareGrantDto
import ru.protolink.communicator.data.api.ShareLinkListItemDto
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** AT-S40/S41/S44 pure helpers (offline). */
class ShareSheetLogicTest {
    @Test
    fun strings_enAndRu_matchUxTables() {
        assertThat(ShareSheetStrings.shareButton("en-US")).isEqualTo("Share")
        assertThat(ShareSheetStrings.shareButton("ru-RU")).isEqualTo("Поделиться")
        assertThat(ShareSheetStrings.guestLinkCreated(null)).isEqualTo("Guest link created")
        assertThat(ShareSheetStrings.guestLinkCreated("ru")).isEqualTo("Гостевая ссылка создана")
        assertThat(ShareSheetStrings.createGuestLink("en")).isEqualTo("+ Create guest link")
        assertThat(ShareSheetStrings.turnOff("en-US")).isEqualTo("Turn off")
        assertThat(ShareSheetStrings.offlineBanner(null)).isEqualTo("Waiting for network…")
        assertThat(ShareSheetStrings.offlineMutationTooltip("en"))
            .isEqualTo("Available when you're back online")
        assertThat(ShareSheetStrings.anyoneOnInternet("en")).isEqualTo("Anyone on the internet")
        assertThat(ShareSheetStrings.anyoneOnInternet("ru")).isEqualTo("Любой в интернете")
        assertThat(ShareAccountStrings.copyMyIdLabel("en")).isEqualTo("Copy my ID")
        assertThat(ShareAccountStrings.copyMyIdLabel("ru-RU")).isEqualTo("Скопировать мой ID")
        assertThat(ShareAccountStrings.copyContactIdLabel("en")).isEqualTo("Copy contact id")
        assertThat(ShareAccountStrings.copyContactIdLabel("ru-RU")).isEqualTo("Скопировать ID контакта")
        assertThat(ShareAccountStrings.contactInformationLabel("en")).isEqualTo("Contact information")
        assertThat(ShareAccountStrings.contactInformationLabel("ru-RU")).isEqualTo("Информация о контакте")
        assertThat(ShareAccountStrings.copyIdLabel("en")).isEqualTo("Copy id")
        assertThat(ShareAccountStrings.copyIdLabel("ru-RU")).isEqualTo("Копировать id")
        assertThat(
            ShareAccountStrings.formatUserIdForClipboard("8F3A1111-2222-3333-4444-555555555555")
        ).isEqualTo("8f3a1111-2222-3333-4444-555555555555")
        assertThat(
            ShareAccountStrings.formatEntityIdForClipboard("8F3A1111-2222-3333-4444-555555555555")
        ).isEqualTo("8f3a1111-2222-3333-4444-555555555555")
        assertThat(ShareAccountStrings.formatUserIdForClipboard("")).isNull()
        assertThat(ShareAccountStrings.formatUserIdForClipboard("not-a-guid")).isNull()
        assertThat(
            ShareAccountStrings.formatUserIdForClipboard("00000000-0000-0000-0000-000000000000")
        ).isNull()
        assertThat(ShareAccountStrings.formatEntityIdForClipboard("")).isNull()
    }

    @Test
    fun contactInformationUrl_matchesEntityLinksFormat() {
        val id = "8F3A1111-2222-3333-4444-555555555555"
        val resolved = ShareAccountStrings.formatUserIdForClipboard(id)
        assertThat(resolved).isEqualTo("8f3a1111-2222-3333-4444-555555555555")
        assertThat(ShareSheetLogic.buildCanonicalLink("https://protolink.ru/", resolved!!))
            .isEqualTo("https://protolink.ru/8f3a1111-2222-3333-4444-555555555555")
        assertThat(ShareAccountStrings.formatUserIdForClipboard(null)).isNull()
        assertThat(ShareAccountStrings.formatUserIdForClipboard("")).isNull()
    }

    @Test
    fun chrome_hasNoTokenGuidOrHttpStatus() {
        assertThat(ShareSheetLogic.assertNoForbiddenUserText(ShareSheetStrings.guestLinksHelper("en")))
            .isTrue()
        assertThat(ShareSheetLogic.assertNoForbiddenUserText(ShareSheetStrings.noGuestLinksYet("ru")))
            .isTrue()
        assertThat(ShareSheetLogic.assertNoForbiddenUserText("bad token here")).isFalse()
        assertThat(ShareSheetLogic.assertNoForbiddenUserText("error 403")).isFalse()
        assertThat(
            ShareSheetLogic.assertNoForbiddenUserText("id 8f3a1111-2222-3333-4444-555555555555")
        ).isFalse()
    }

    @Test
    fun linkVersusGuestLink_helpersDiffer() {
        val link = ShareSheetStrings.linkHelper("en")
        val guest = ShareSheetStrings.guestLinksHelper("en")
        assertThat(link.lowercase()).contains("already have access")
        assertThat(guest.lowercase()).contains("no account")
        assertThat(link.lowercase()).doesNotContain("guest")
    }

    @Test
    fun subtreeLine_foldersOnly() {
        assertThat(ShareSheetLogic.showSubtreeLine(isFolder = true)).isTrue()
        assertThat(ShareSheetLogic.showSubtreeLine(isFolder = false)).isFalse()
        assertThat(ShareSheetStrings.subtitle("en", isFolder = true, childCount = 12))
            .contains("12")
        assertThat(ShareSheetStrings.subtitle("en", isFolder = true, childCount = 12))
            .contains("Folder")
        assertThat(ShareSheetStrings.subtitle("en", isFolder = false, childCount = 0))
            .isEqualTo("File")
        assertThat(ShareSheetStrings.subtreeLine("en")).contains("everything added")
    }

    @Test
    fun peopleFromGrants_excludesGuestLink_marksCreator() {
        val creator = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val people = ShareSheetLogic.peopleFromGrants(
            listOf(
                ShareGrantDto(
                    permissionForId = creator,
                    principalKind = "user",
                    displayName = "Ivan",
                    canWrite = true
                ),
                ShareGrantDto(principalKind = "guest_link", displayName = "link"),
                ShareGrantDto(principalKind = "public", displayName = "public")
            ),
            creatorUserId = creator
        )
        assertThat(people).hasSize(2)
        assertThat(people[0].isCreator).isTrue()
        assertThat(people[1].isCreator).isFalse()
        assertThat(ShareSheetLogic.personDisplayName("en", people[1].grant))
            .isEqualTo("Anyone on the internet")
    }

    @Test
    fun peopleFromGrants_cloudRootNullCreator_marksSelfWriteAsOwner() {
        val me = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val other = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val people = ShareSheetLogic.peopleFromGrants(
            listOf(
                ShareGrantDto(
                    permissionForId = me,
                    principalKind = "user",
                    displayName = "Me",
                    canWrite = true
                ),
                ShareGrantDto(
                    permissionForId = other,
                    principalKind = "user",
                    displayName = "Other",
                    canWrite = false
                )
            ),
            creatorUserId = null,
            currentUserId = me
        )
        assertThat(people).hasSize(2)
        assertThat(people[0].isCreator).isTrue()
        assertThat(people[1].isCreator).isFalse()
    }

    @Test
    fun partitionLinks_newestLiveFirst() {
        val parts = ShareSheetLogic.partitionLinks(
            listOf(
                ShareLinkListItemDto(id = "1", isLive = false, createdAt = "2026-01-01T00:00:00Z", tokenPrefix = "aaa"),
                ShareLinkListItemDto(
                    id = "2",
                    isLive = true,
                    createdAt = "2026-10-01T00:00:00Z",
                    tokenPrefix = "bbb",
                    label = "For contractor"
                ),
                ShareLinkListItemDto(id = "3", isLive = true, createdAt = "2026-09-01T00:00:00Z", tokenPrefix = "ccc")
            )
        )
        assertThat(parts.live).hasSize(2)
        assertThat(parts.live[0].id).isEqualTo("2")
        assertThat(parts.inactive).hasSize(1)
        assertThat(ShareSheetLogic.linkRowTitle("en", "For contractor", "x7Qe2a"))
            .isEqualTo("For contractor · …x7Qe2a")
        assertThat(ShareSheetLogic.linkRowTitle("en", null, "k1Pz9d").lowercase())
            .doesNotContain("token")
    }

    @Test
    fun gating_offlineAndViewer() {
        assertThat(ShareSheetLogic.canMutate(isOnline = false, canEdit = true, showCreatedPanel = false))
            .isFalse()
        assertThat(ShareSheetLogic.canMutate(isOnline = true, canEdit = false, showCreatedPanel = false))
            .isFalse()
        assertThat(ShareSheetLogic.canMutate(isOnline = true, canEdit = true, showCreatedPanel = true))
            .isFalse()
        assertThat(ShareSheetLogic.canMutate(isOnline = true, canEdit = true, showCreatedPanel = false))
            .isTrue()
        assertThat(ShareSheetLogic.showCreateButton(true, false, false, 20)).isFalse()
        assertThat(ShareSheetLogic.showLinkLimit(20)).isTrue()
    }

    @Test
    fun expiryPayload_andCanonicalLink() {
        val never = ShareSheetLogic.expiryPayload(0)
        assertThat(never.neverExpires).isTrue()
        assertThat(never.expiresAtIso).isNull()
        val d30 = ShareSheetLogic.expiryPayload(30, Instant.parse("2026-10-20T00:00:00Z"))
        assertThat(d30.neverExpires).isFalse()
        assertThat(d30.expiresAtIso).isNotNull()
        assertThat(
            ShareSheetLogic.buildCanonicalLink(
                "https://protolink.ru/",
                "0f8fad5b-d9cb-469f-a165-70867728950e"
            )
        ).isEqualTo("https://protolink.ru/0f8fad5b-d9cb-469f-a165-70867728950e")
        assertThat(
            ShareSheetLogic.isExpiringSoon(
                Instant.now().plusSeconds(3600).toString()
            )
        ).isTrue()
    }

    @Test
    fun expiryLine_formatsLikeWindows() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.UK)
        try {
            val zone = ZoneOffset.UTC
            val now = Instant.parse("2026-10-22T12:00:00Z")
            assertThat(ShareSheetLogic.expiryLine("en", expiresAtIso = null, now = now, zone = zone))
                .isEqualTo("Never expires")
            assertThat(ShareSheetLogic.expiryLine("ru", expiresAtIso = null, now = now, zone = zone))
                .isEqualTo("Без срока")

            val inFiveHours = Instant.parse("2026-10-22T17:00:00Z").toString()
            assertThat(
                ShareSheetLogic.expiryLine("en", inFiveHours, now = now, zone = zone)
            ).isEqualTo("Expires in 5 hours")
            assertThat(
                ShareSheetLogic.expiryLine("ru", inFiveHours, now = now, zone = zone)
            ).isEqualTo("Истекает через 5 ч")
            assertThat(ShareSheetLogic.isExpiringSoon(inFiveHours, now)).isTrue()

            val nextMonth = Instant.parse("2026-11-16T15:00:00Z").toString()
            assertThat(
                ShareSheetLogic.expiryLine("en", nextMonth, now = now, zone = zone)
            ).isEqualTo("Expires 16 Nov")
            assertThat(ShareSheetLogic.isExpiringSoon(nextMonth, now)).isFalse()

            val yesterday = Instant.parse("2026-10-21T10:00:00Z").toString()
            assertThat(
                ShareSheetLogic.expiryLine("en", yesterday, now = now, zone = zone)
            ).startsWith("Expired ")

            val revoked = Instant.parse("2026-10-22T09:30:00Z").toString()
            assertThat(
                ShareSheetLogic.expiryLine(
                    "en",
                    expiresAtIso = nextMonth,
                    revokedAtIso = revoked,
                    now = now,
                    zone = zone
                )
            ).isEqualTo("Turned off 09:30")
        } finally {
            Locale.setDefault(prev)
        }
    }

    /** Api List payloads often omit Z (DateTime Unspecified) — Instant.parse alone fails. */
    @Test
    fun expiryLine_acceptsApiUnspecifiedIsoWithoutZ() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.UK)
        try {
            val zone = ZoneOffset.UTC
            val now = Instant.parse("2026-10-22T12:00:00Z")

            assertThat(
                ShareSheetLogic.expiryLine("en", "2026-11-16T15:00:00", now = now, zone = zone)
            ).isEqualTo("Expires 16 Nov")
            assertThat(
                ShareSheetLogic.expiryLine(
                    "en",
                    "2026-11-16T15:00:00.1234567",
                    now = now,
                    zone = zone
                )
            ).isEqualTo("Expires 16 Nov")
            assertThat(ShareSheetLogic.isExpiringSoon("2026-11-16T15:00:00", now)).isFalse()

            assertThat(
                ShareSheetLogic.expiryLine("en", "2026-10-22T17:00:00", now = now, zone = zone)
            ).isEqualTo("Expires in 5 hours")
            // Fractional no-Z mid-window (ceil of ~4.75h → 5); do not use exact 17:00.xxx (ceil → 6).
            assertThat(
                ShareSheetLogic.expiryLine("ru", "2026-10-22T16:45:00.5949261", now = now, zone = zone)
            ).isEqualTo("Истекает через 5 ч")
            assertThat(ShareSheetLogic.isExpiringSoon("2026-10-22T17:00:00", now)).isTrue()
            assertThat(ShareSheetLogic.isExpiringSoon("2026-10-22T16:45:00.5949261", now)).isTrue()

            assertThat(ShareSheetLogic.expiryLine("en", expiresAtIso = null, now = now, zone = zone))
                .isEqualTo("Never expires")
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun footerMentionsFifteenMinutes() {
        assertThat(ShareSheetStrings.footerLag("en")).contains("15")
        assertThat(ShareSheetStrings.footerLag("ru-RU")).contains("15")
    }
}
