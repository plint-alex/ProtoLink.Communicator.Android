package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UX-S-AM* — Share → Messenger send helpers + RU/EN chrome. */
class ShareSendInMessengerTest {
    @Test
    fun normalizeUrl_trimsAndRejectsBlank() {
        assertThat(ShareSendInMessenger.normalizeUrl("  https://protolink.ru/g/abc  "))
            .isEqualTo("https://protolink.ru/g/abc")
        assertThat(ShareSendInMessenger.normalizeUrl("")).isNull()
        assertThat(ShareSendInMessenger.normalizeUrl("   ")).isNull()
        assertThat(ShareSendInMessenger.normalizeUrl(null)).isNull()
    }

    @Test
    fun canonicalUrl_buildsEntityLink() {
        val id = "8f3a1111-2222-3333-4444-555555555555"
        assertThat(ShareSendInMessenger.canonicalUrl("https://protolink.ru/", id))
            .isEqualTo(ShareSheetLogic.buildCanonicalLink("https://protolink.ru/", id))
        assertThat(ShareSendInMessenger.canonicalUrl("https://protolink.ru/", "  ")).isNull()
        assertThat(ShareSendInMessenger.canonicalUrl("https://protolink.ru/", null)).isNull()
    }

    @Test
    fun canOffer_requiresUrlAndContacts() {
        assertThat(ShareSendInMessenger.canOffer("https://x", 1)).isTrue()
        assertThat(ShareSendInMessenger.canOffer("https://x", 0)).isFalse()
        assertThat(ShareSendInMessenger.canOffer(null, 2)).isFalse()
        assertThat(ShareSendInMessenger.canOffer("  ", 2)).isFalse()
    }

    @Test
    fun sendInMessenger_strings_enRu_noGuidToken() {
        assertThat(ShareSheetStrings.sendInMessenger("en")).isEqualTo("Send in Messenger")
        assertThat(ShareSheetStrings.sendInMessenger("ru")).isEqualTo("Отправить в Мессенджере")
        assertThat(ShareSheetStrings.sendInMessengerPick("en")).isEqualTo("Who should get the link?")
        assertThat(ShareSheetStrings.sendInMessengerPick("ru")).isEqualTo("Кому отправить ссылку?")
        assertThat(ShareSheetStrings.sendInMessengerNoContacts("en"))
            .isEqualTo("Add a Messenger contact first.")
        assertThat(ShareSheetStrings.sendInMessengerNoContacts("ru"))
            .isEqualTo("Сначала добавьте контакт в Мессенджере.")
        for (s in listOf(
            ShareSheetStrings.sendInMessenger("en"),
            ShareSheetStrings.sendInMessenger("ru"),
            ShareSheetStrings.sendInMessengerPick("en"),
            ShareSheetStrings.sendInMessengerPick("ru"),
            ShareSheetStrings.sendInMessengerNoContacts("en"),
            ShareSheetStrings.sendInMessengerNoContacts("ru")
        )) {
            assertThat(ShareSheetLogic.assertNoForbiddenUserText(s)).isTrue()
        }
    }
}
