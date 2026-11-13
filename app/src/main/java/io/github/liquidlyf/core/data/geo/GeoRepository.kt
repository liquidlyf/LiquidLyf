package io.github.liquidlyf.core.data.geo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.GeoArea
import io.github.liquidlyf.core.model.GeoDistrict
import io.github.liquidlyf.core.model.GeoDivision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Divisions, districts and upazilas/city corporations from the bundled assets/bd-geo.json.
 * No network and 0 Firestore reads (doc 10 §5.1 rule 5).
 */
interface GeoRepository {
    suspend fun geo(): BdGeo
}

/** The parsed list, with lookups by code. */
class BdGeo(val divisions: List<GeoDivision>) {
    private val districtsByCode: Map<String, GeoDistrict> =
        divisions.flatMap { it.districts }.associateBy { it.code }
    private val areasByCode: Map<String, GeoArea> =
        districtsByCode.values.flatMap { it.areas }.associateBy { it.code }

    fun division(code: String): GeoDivision? = divisions.firstOrNull { it.code == code }

    fun district(code: String): GeoDistrict? = districtsByCode[code]

    fun area(code: String): GeoArea? = areasByCode[code]

    /** True when the three codes exist and nest: the area is in the district, the district in the division. */
    fun isValid(area: Area): Boolean {
        val district = districtsByCode[area.district] ?: return false
        return district.divisionCode == area.division && areasByCode[area.upazila]?.districtCode == district.code
    }

    /** Neighbouring district codes for the request fan-out (tier 4). */
    fun neighbours(districtCode: String): List<String> = districtsByCode[districtCode]?.neighbours.orEmpty()

    /** "Jhikargachha, Jashore", or just the district without an area; the code if it's unknown. */
    fun placeLabel(district: String, area: String?, locale: AppLocale): String {
        val districtName = districtsByCode[district]?.name?.localized(locale) ?: district
        val areaName = area?.let { areasByCode[it]?.name?.localized(locale) } ?: return districtName
        return "$areaName, $districtName"
    }
}

@Singleton
class AssetGeoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GeoRepository {
    private val mutex = Mutex()
    private var cached: BdGeo? = null

    override suspend fun geo(): BdGeo = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            context.assets.open(ASSET).bufferedReader().use { BdGeoParser.parse(it.readText()) }
        }.also { cached = it }
    }

    companion object {
        const val ASSET = "bd-geo.json"
    }
}
