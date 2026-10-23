package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AuthFlow Login → web forgot-password URL (offline; no browser). */
class PublicSitePathsTest {

    @Test
    fun buildForgotPasswordUrl_usesDefaultWhenBaseMissing() {
        assertThat(PublicSitePaths.buildForgotPasswordUrl(null))
            .isEqualTo("https://protolink.ru/forgot-password")
        assertThat(PublicSitePaths.buildForgotPasswordUrl("  "))
            .isEqualTo("https://protolink.ru/forgot-password")
    }

    @Test
    fun buildForgotPasswordUrl_trimsTrailingSlash() {
        assertThat(PublicSitePaths.buildForgotPasswordUrl("https://stand.example/"))
            .isEqualTo("https://stand.example/forgot-password")
    }

    @Test
    fun buildForgotPasswordUrl_keepsConfiguredBase() {
        assertThat(PublicSitePaths.buildForgotPasswordUrl("https://protolink.ru/"))
            .isEqualTo("https://protolink.ru/forgot-password")
    }
}
