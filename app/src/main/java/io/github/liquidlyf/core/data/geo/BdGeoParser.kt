package io.github.liquidlyf.core.data.geo

import io.github.liquidlyf.core.model.GeoArea
import io.github.liquidlyf.core.model.GeoAreaType
import io.github.liquidlyf.core.model.GeoDistrict
import io.github.liquidlyf.core.model.GeoDivision
import io.github.liquidlyf.core.model.PlaceName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads assets/bd-geo.json, written by app/scripts/build_bd_geo.py. */
object BdGeoParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): BdGeo {
        val file = json.decodeFromString<FileJson>(text)
        return BdGeo(
            file.divisions.map { v ->
                GeoDivision(
                    code = v.code,
                    name = PlaceName(v.nameEn, v.nameBn, v.bnNeedsReview),
                    districts = v.districts.map { d ->
                        GeoDistrict(
                            code = d.code,
                            name = PlaceName(d.nameEn, d.nameBn, d.bnNeedsReview),
                            divisionCode = v.code,
                            neighbours = d.neighbours,
                            areas = d.upazilas.map { u ->
                                GeoArea(
                                    code = u.code,
                                    name = PlaceName(u.nameEn, u.nameBn, u.bnNeedsReview),
                                    type = if (u.type == "CITY_CORPORATION") GeoAreaType.CITY_CORPORATION else GeoAreaType.UPAZILA,
                                    districtCode = d.code,
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    @Serializable
    private class FileJson(val version: Int, val divisions: List<DivisionJson>)

    @Serializable
    private class DivisionJson(
        val code: String,
        val nameEn: String,
        val nameBn: String? = null,
        val bnNeedsReview: Boolean = false,
        val districts: List<DistrictJson>,
    )

    @Serializable
    private class DistrictJson(
        val code: String,
        val nameEn: String,
        val nameBn: String? = null,
        val bnNeedsReview: Boolean = false,
        val neighbours: List<String> = emptyList(),
        val upazilas: List<UpazilaJson>,
    )

    @Serializable
    private class UpazilaJson(
        val code: String,
        val nameEn: String,
        val nameBn: String? = null,
        val bnNeedsReview: Boolean = false,
        val type: String = "UPAZILA",
    )
}
