package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntityLinkUnfurlTest {
    private val idA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val idB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    @Test
    fun unfurl_findsPublicSiteGuid_ignoresExternal() {
        val baseUrl = "https://protolink.ru/"
        val text = "see https://protolink.ru/$idA/contract?lang=en and https://example.com/$idB"
        val ids = EntityLinkUnfurl.findEntityIds(text, baseUrl)
        assertThat(ids).containsExactly(idA)

        assertThat(EntityLinkUnfurl.tryParseEntityUrl("https://protolink.ru/$idA/slug", baseUrl))
            .isEqualTo(idA)
        assertThat(EntityLinkUnfurl.tryParseEntityUrl("https://evil.example/$idA", baseUrl)).isNull()
    }

    @Test
    fun unfurl_defaultBase_whenNullSettings() {
        val ids = EntityLinkUnfurl.findEntityIds(
            "https://protolink.ru/$idA",
            publicSiteBaseUrl = null
        )
        assertThat(ids).containsExactly(idA)
    }

    @Test
    fun unfurl_noExternalUrls() {
        val ids = EntityLinkUnfurl.findEntityIds(
            "https://google.com/$idA and http://other/$idB",
            "https://protolink.ru/"
        )
        assertThat(ids).isEmpty()
    }
}
