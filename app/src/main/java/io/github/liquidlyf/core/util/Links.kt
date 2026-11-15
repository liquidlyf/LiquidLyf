package io.github.liquidlyf.core.util

import io.github.liquidlyf.core.model.AppLocale

/** Public pages on the website (session C's docs/website-drafts, published on GitHub Pages). */
object Links {
    private const val SITE = "https://liquidlyf.github.io"

    /** The project's public contact (never a maintainer's personal address). */
    const val SUPPORT_EMAIL = "liquidlyf@gmail.com"

    /**
     * Stored with each profile as `termsVersion`: the effective date of the published Privacy
     * Policy and Terms (liquidlyf.github.io, 15 Nov 2026). Only new profiles record it; there is
     * no re-accept prompt yet. Before the first change to the Terms after launch, add one that
     * asks people whose termsVersion is older.
     */
    const val TERMS_VERSION = "2026-11-15"

    fun terms(locale: AppLocale): String = page(locale, "terms")

    fun privacy(locale: AppLocale): String = page(locale, "privacy")

    /** How account deletion works, for people without the app (Phase 8.2). */
    fun deleteAccount(locale: AppLocale): String = page(locale, "delete-account")

    /** AGPL section 13: the official app's source. A fork must point this to its own source. */
    const val SOURCE_CODE = "https://github.com/liquidlyf/LiquidLyf"

    /** The download page (Phase 10.3) that "Share LiquidLyf" links to. */
    fun download(locale: AppLocale): String = page(locale, "download")

    private fun page(locale: AppLocale, path: String) =
        if (locale == AppLocale.BN) "$SITE/bn/$path" else "$SITE/$path"
}
