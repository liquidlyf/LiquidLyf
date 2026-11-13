package io.github.liquidlyf.core.data.hospital

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.PlaceName
import io.github.liquidlyf.core.model.RequestLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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
 * The curated hospital list, bundled as `assets/hospitals.json` (built from
 * firebase/seed/hospitals.csv). Until that list exists the search finds nothing and people type
 * the hospital's name, which the request form always allows ("Other").
 */
interface HospitalRepository {
    /** Up to [limit] hospitals in [district] whose name contains [query], best matches first. */
    suspend fun search(district: String, query: String, limit: Int = 8): List<Hospital>
}

@Singleton
class AssetHospitalRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HospitalRepository {
    private val mutex = Mutex()
    private var cache: List<Hospital>? = null

    override suspend fun search(district: String, query: String, limit: Int): List<Hospital> =
        HospitalSearch.search(all(), district, query, limit)

    private suspend fun all(): List<Hospital> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            try {
                context.assets.open(ASSET).bufferedReader().use { HospitalSearch.parse(it.readText()) }
            } catch (_: FileNotFoundException) {
                emptyList()
            }
        }.also { cache = it }
    }

    private companion object {
        const val ASSET = "hospitals.json"
    }
}

/** Pure parsing and matching, unit-tested. */
object HospitalSearch {
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

    fun parse(text: String): List<Hospital> = json.decodeFromString<List<Row>>(text).map { r ->
        Hospital(
            id = r.id,
            name = PlaceName(en = r.name, bn = r.nameBn),
            address = r.address,
            district = r.district,
            upazila = r.upazila,
            location = if (r.lat != null && r.lng != null && r.geohash != null) RequestLocation(r.lat, r.lng, r.geohash) else null,
        )
    }

    /** Names starting with the query come before names that only contain it; blank finds nothing. */
    fun search(all: List<Hospital>, district: String, query: String, limit: Int): List<Hospital> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.district == district }
            .mapNotNull { h ->
                val names = listOfNotNull(h.name.en, h.name.bn).map { it.lowercase(Locale.ROOT) }
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

    fun label(h: Hospital, locale: AppLocale): String = h.name.localized(locale)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HospitalModule {
    @Binds abstract fun hospitals(impl: AssetHospitalRepository): HospitalRepository
}
