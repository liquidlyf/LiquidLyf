package io.github.liquidlyf.core.util

import io.github.liquidlyf.core.model.AppLocale

/** Public pages on the website (session C's docs/website-drafts, published on GitHub Pages). */
object Links {
    private const val SITE = "https://liquidlyf.github.io"

    /**
     * Stored with each profile as `termsVersion`. Change it when the Terms change in a way users
     * must accept again (Phase 8 asks them). The published Terms have no effective date yet.
     */
    const val TERMS_VERSION = "draft-2026-11"

    fun terms(locale: AppLocale): String = page(locale, "terms")

    fun privacy(locale: AppLocale): String = page(locale, "privacy")

    private fun page(locale: AppLocale, path: String) =
        if (locale == AppLocale.BN) "$SITE/bn/$path" else "$SITE/$path"
}
