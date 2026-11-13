package io.github.liquidlyf.core.firebase.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant

/*
 * Reading and writing raw Firestore maps.
 *
 * The app maps documents by hand instead of with Firestore's reflection mapper: the rules check
 * the exact set of keys in every write (`keys().hasOnly`), explicit maps make that visible and
 * testable, and nothing needs R8 keep rules. Reads are lenient: a missing or malformed required
 * field makes the whole document unreadable (null), never a crash.
 */

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()

internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

/** Null for a missing field, and for a server timestamp still pending in the local cache. */
internal fun Map<String, Any?>.instant(key: String): Instant? = (this[key] as? Timestamp)?.toInstantCompat()

internal fun Map<String, Any?>.strings(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

/** Timestamp.toInstant() is API 26+ only; this works on every API level. */
internal fun Timestamp.toInstantCompat(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

internal fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

/** The rules compare these with `request.time`. */
internal fun serverTime(): FieldValue = FieldValue.serverTimestamp()

/** Builds a write map, leaving out null values (the rules accept absent optional fields). */
internal fun fields(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
    buildMap { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }
