package io.github.liquidlyf.core.data.hospital

import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.PlaceName
import io.github.liquidlyf.core.model.RequestLocation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** A hospital from the curated list (replaces v0's Geocoder guesses: BUG-011–014). */
data class Hospital(
    val id: String,
    val name: PlaceName,
    val address: String?,
    val district: String,
    val upazila: String?,
    val location: RequestLocation?,
)

/**
 * The curated hospital list (plan D14): `firebase/seed/hospitals.csv` → B's script → one
 * `hospitals.json`, built into the app (`assets/`) and published on the website. [version] goes up
 * by 1 at every published change and never goes down, so the higher one is always the newer list.
 */
data class HospitalList(val version: Int, val hospitals: List<Hospital>) {
    companion object {
        /** No list yet: the search finds nothing and people type the hospital's name ("Other"). */
        val EMPTY = HospitalList(version = 0, hospitals = emptyList())
    }
}

/** What the request form searches. [HospitalLists] is the real one; tests use a fake. */
interface HospitalRepository {
    /** Up to [limit] hospitals in [district] whose name contains [query], best matches first. */
    suspend fun search(district: String, query: String, limit: Int = 8): List<Hospital>
}

/** Pure parsing and matching, unit-tested. */
object HospitalSearch {
    /** `{"version": 3, "hospitals": [ … ]}`. Fields the app doesn't use yet (type, hasBloodBank) are ignored. */
    @Serializable
    private class FileJson(val version: Int, val hospitals: List<Row>)

    @Serializable
    private class Row(
        val id: String,
        val name: String,
        val nameBn: String? = null,
        val address: String? = null,
        val district: String,
        val upazila: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val geohash: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The same limit as B's tests on hospitals.csv. */
    const val MAX_NAME = 120

    /** Bangladesh with a margin: a map point outside it is a typo, and "Open in Maps" would mislead. */
    private val LAT = 20.0..27.0
    private val LNG = 88.0..93.0

    /**
     * The list in [text], or an exception if it isn't well formed: a version below 1, a blank or
     * repeated id, a blank or over-long name, part of a map point, or a point outside Bangladesh.
     * Whether its area codes exist is [HospitalListRules]' job (it needs bd-geo).
     */
    fun parse(text: String): HospitalList {
        val file = json.decodeFromString<FileJson>(text)
        require(file.version >= 1) { "version ${file.version}" }
        val ids = HashSet<String>()
        val hospitals = file.hospitals.map { r ->
            require(r.id.isNotBlank() && ids.add(r.id)) { "id '${r.id}' blank or repeated" }
            require(r.name.isNotBlank() && r.name.length <= MAX_NAME) { "${r.id}: name" }
            require(r.nameBn == null || (r.nameBn.isNotBlank() && r.nameBn.length <= MAX_NAME)) { "${r.id}: nameBn" }
            val point = listOf(r.lat, r.lng, r.geohash)
            require(point.all { it == null } || point.none { it == null }) { "${r.id}: part of a map point" }
            val location = if (r.lat != null && r.lng != null && r.geohash != null) {
                require(r.lat in LAT && r.lng in LNG && r.geohash.isNotBlank()) { "${r.id}: map point outside Bangladesh" }
                RequestLocation(r.lat, r.lng, r.geohash)
            } else {
                null
            }
            Hospital(
                id = r.id,
                name = PlaceName(en = r.name, bn = r.nameBn),
                address = r.address?.takeIf { it.isNotBlank() },
                district = r.district,
                upazila = r.upazila?.takeIf { it.isNotBlank() },
                location = location,
            )
        }
        return HospitalList(file.version, hospitals)
    }

    fun parseOrNull(text: String): HospitalList? = runCatching { parse(text) }.getOrNull()

    /** Names starting with the query come before names that only contain it; blank finds nothing. */
    fun search(all: List<Hospital>, district: String, query: String, limit: Int): List<Hospital> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.district == district }
            .mapNotNull { h ->
                val names = listOfNotNull(h.name.en, h.name.bn).map(::fold)
                when {
                    names.any { it.startsWith(q) } -> 0 to h
                    names.any { q in it } -> 1 to h
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.first }, { it.second.name.en }))
            .take(limit)
            .map { it.second }
            .toList()
    }

    /**
     * One form for comparing: ড়, ঢ় and য় come either as one character or as the letter plus a nukta
     * (keyboards and lists differ), so both sides go to NFC, then lower case.
     */
    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun label(h: Hospital, locale: AppLocale): String = h.name.localized(locale)
}

/** When a downloaded list may replace the one the phone uses (plan D14). */
object HospitalListRules {
    sealed interface Verdict {
        /** Newer and sound: save it and use it. */
        data object Accept : Verdict

        /** The same version or an older one (the app has a newer list built in): keep ours. */
        data object NotNewer : Verdict

        /** Broken or suspicious: keep ours, and look again tomorrow. */
        data class Reject(val reason: String) : Verdict
    }

    /**
     * [candidate] is null when the file didn't parse. A newer list must name only districts in
     * bd-geo and upazilas inside their district, and hold at least 80% as many hospitals as
     * [current]: a half-empty file is far more likely a mistake than half the hospitals closing.
     */
    fun check(candidate: HospitalList?, current: HospitalList, geo: BdGeo): Verdict {
        if (candidate == null) return Verdict.Reject("not a well-formed hospital list")
        if (candidate.version <= current.version) return Verdict.NotNewer
        if (candidate.hospitals.isEmpty()) return Verdict.Reject("no hospitals")
        if (candidate.hospitals.size * 5 < current.hospitals.size * 4) {
            return Verdict.Reject("${candidate.hospitals.size} hospitals, under 80% of ${current.hospitals.size}")
        }
        candidate.hospitals.forEach { h ->
            if (geo.district(h.district) == null) return Verdict.Reject("${h.id}: district ${h.district}")
            if (h.upazila != null && geo.area(h.upazila)?.districtCode != h.district) {
                return Verdict.Reject("${h.id}: upazila ${h.upazila} isn't in ${h.district}")
            }
        }
        return Verdict.Accept
    }
}
