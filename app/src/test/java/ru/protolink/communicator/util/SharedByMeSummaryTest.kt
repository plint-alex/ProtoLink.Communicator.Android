package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedByMeSummaryTest {

    @Test
    fun rowSubtitle_peopleGuestsPublic() {
        val en = SharedByMeSummary.rowSubtitle(
            lang = "en",
            peopleNames = listOf("Alice", "Bob"),
            peopleMoreCount = 2,
            liveGuestLinkCount = 1,
            hasPublicAccess = true
        )
        assertThat(en).isEqualTo("Alice, Bob +2 more · 1 guest link · Anyone on the internet")

        val ru = SharedByMeSummary.rowSubtitle(
            lang = "ru-RU",
            peopleNames = listOf("Анна"),
            peopleMoreCount = 0,
            liveGuestLinkCount = 3,
            hasPublicAccess = false
        )
        assertThat(ru).isEqualTo("Анна · 3 гостевых ссылок")
    }

    @Test
    fun rowSubtitle_guestsOnly_and_empty() {
        assertThat(
            SharedByMeSummary.rowSubtitle("en", emptyList(), 0, 2, false)
        ).isEqualTo("2 guest links")
        assertThat(
            SharedByMeSummary.rowSubtitle("en", emptyList(), 0, 0, false)
        ).isEmpty()
        assertThat(
            SharedByMeSummary.rowSubtitle("en", emptyList(), 0, 0, true)
        ).isEqualTo("Anyone on the internet")
    }

    @Test
    fun sharedByMe_strings_enRuParity() {
        assertThat(ShareSheetCloudStrings.sharedByMe("en")).isEqualTo("Shared by me")
        assertThat(ShareSheetCloudStrings.sharedByMe("ru")).isEqualTo("Я поделился")
        assertThat(ShareSheetCloudStrings.emptySharedByMeTitle("en"))
            .isEqualTo("Nothing shared yet")
        assertThat(ShareSheetCloudStrings.emptySharedByMeBody("en"))
            .isEqualTo("When you give someone access, it shows up here.")
        assertThat(ShareSheetCloudStrings.emptySharedByMeTitle("ru-RU"))
            .isEqualTo("Пока ничем не поделились")
        assertThat(ShareSheetCloudStrings.emptySharedByMeBody("ru"))
            .contains("здесь")
    }

    @Test
    fun sharedByMe_virtualNode_distinctAndFlagged() {
        assertThat(CloudVirtualNodes.SHARED_BY_ME_ID)
            .isNotEqualTo(CloudVirtualNodes.SHARED_WITH_ME_ID)
        assertThat(CloudVirtualNodes.SHARED_BY_ME_ID)
            .isNotEqualTo(CloudVirtualNodes.RECENT_ID)
        assertThat(CloudVirtualNodes.isVirtual(CloudVirtualNodes.SHARED_BY_ME_ID)).isTrue()
    }
}
