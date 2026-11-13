package io.github.liquidlyf.core.model

/**
 * The bundled list of Bangladesh areas (assets/bd-geo.json, Phase 2.4). Codes are the official
 * BBS pcodes as published by UN OCHA HDX ("BD30", "BD3026", "BD30262000"); they go into
 * Firestore and FCM topic names, so they never change.
 */
data class GeoDivision(
    val code: String,
    val name: PlaceName,
    val districts: List<GeoDistrict>,
)

data class GeoDistrict(
    val code: String,
    val name: PlaceName,
    val divisionCode: String,
    /** Districts sharing a border: tier 4 of the request fan-out. */
    val neighbours: List<String>,
    val areas: List<GeoArea>,
)

/** What `Area.upazila` points to: an upazila, or a whole city corporation. */
data class GeoArea(
    val code: String,
    val name: PlaceName,
    val type: GeoAreaType,
    val districtCode: String,
)

enum class GeoAreaType { UPAZILA, CITY_CORPORATION }

data class PlaceName(
    val en: String,
    /** Null only if no Bangla name exists yet; then show [en]. */
    val bn: String?,
    /** LiquidLyf's own Bangla, not yet checked by a native speaker. */
    val bnNeedsReview: Boolean = false,
) {
    fun localized(locale: AppLocale): String = if (locale == AppLocale.BN) bn ?: en else en
}
