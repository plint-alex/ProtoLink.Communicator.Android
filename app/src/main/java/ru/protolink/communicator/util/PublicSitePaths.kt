package ru.protolink.communicator.util

/**
 * Public Client SPA paths under [EntityLinkUnfurl.DEFAULT_PUBLIC_SITE_BASE_URL].
 * Mirrors Communicator.Windows `PublicSitePaths` for MVP native → web reset.
 */
object PublicSitePaths {
    const val FORGOT_PASSWORD = "forgot-password"

    /** Absolute URL for the web forgot-password page (MVP reset entry for Login). */
    fun buildForgotPasswordUrl(publicSiteBaseUrl: String?): String {
        val base = EntityLinkUnfurl.normalizeBase(publicSiteBaseUrl)
        return "$base/$FORGOT_PASSWORD"
    }
}
