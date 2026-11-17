package io.github.liquidlyf.core.firebase

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import io.github.liquidlyf.core.util.AppError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/*
 * Firestore listeners as Flows. The listener is removed as soon as the collector stops
 * (screen left, ViewModel cleared), so listeners can no longer pile up (BUG-008).
 * Errors close the flow: every collector ends it with [endOnListenError].
 */

/**
 * Ends a listener flow quietly when Firestore closes it with an error, instead of crashing the
 * collecting coroutine: logs the kind of error and runs [onError], which may emit a last value.
 * The usual case is PERMISSION_DENIED for a screen still listening while the user signs out
 * (A16, 15 Nov 2026: that crashed the app).
 */
fun <T> Flow<T>.endOnListenError(what: String, onError: suspend FlowCollector<T>.(AppError) -> Unit = {}): Flow<T> =
    catch { e ->
        val error = CrashReports.appError(e)
        Log.w("LiquidLyfFirestore", "$what listener stopped: $error")
        onError(error)
    }

/**
 * A document as a screen needs to know it (STATE-05): there, surely gone (the server says so), or
 * not known yet. Offline, a document that isn't saved on the phone first reads as "doesn't exist"
 * from the cache; that is [Unknown], not "removed".
 */
sealed interface DocState<out T> {
    data class Found<T>(val value: T) : DocState<T>

    data object Missing : DocState<Nothing>

    data object Unknown : DocState<Nothing>
}

/** Like [snapshots], but tells a "doesn't exist" from the server apart from one from the cache. */
fun <T : Any> DocumentReference.docStates(mapper: (DocumentSnapshot) -> T?): Flow<DocState<T>> = callbackFlow {
    // Metadata changes too: the server's "still doesn't exist" changes nothing but isFromCache.
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot == null) return@addSnapshotListener
        val value = if (snapshot.exists()) mapper(snapshot) else null
        trySend(
            when {
                value != null -> DocState.Found(value)
                !snapshot.exists() && snapshot.metadata.isFromCache -> DocState.Unknown
                else -> DocState.Missing
            },
        )
    }
    awaitClose { registration.remove() }
}.distinctUntilChanged()

/** Emits the mapped document on every change, or null while it doesn't exist. */
fun <T> DocumentReference.snapshots(mapper: (DocumentSnapshot) -> T?): Flow<T?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(if (snapshot.exists()) mapper(snapshot) else null)
    }
    awaitClose { registration.remove() }
}

/** Emits the documents mapped to [T] on every change. Documents the mapper rejects are skipped. */
fun <T : Any> Query.snapshots(mapper: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot.documents.mapNotNull(mapper))
    }
    awaitClose { registration.remove() }
}

/**
 * Like [snapshots], but null while all it has is the phone's cache and that's empty: then it doesn't
 * know yet (a fresh install offline said "No one near you needs B+", D's re-audit). Once the server
 * answers, or the cache has something, it's the list.
 */
fun <T : Any> Query.knownSnapshots(mapper: (DocumentSnapshot) -> T?): Flow<List<T>?> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) {
            trySend(if (snapshot.metadata.isFromCache && snapshot.isEmpty) null else snapshot.documents.mapNotNull(mapper))
        }
    }
    awaitClose { registration.remove() }
}.distinctUntilChanged()

/**
 * One read, from the local cache when the document is there (0 billed reads), otherwise from the
 * server (doc 10 §5.1 rule 2). [refresh] skips the cache, for pull-to-refresh.
 */
suspend fun DocumentReference.getCacheFirst(refresh: Boolean = false): DocumentSnapshot {
    if (!refresh) {
        try {
            val cached = get(Source.CACHE).await()
            if (cached.exists()) return cached
        } catch (_: FirebaseFirestoreException) {
            // Not in the cache: fall through to the server.
        }
    }
    return get(Source.DEFAULT).await()
}

/** [snapshots] that maps with Firestore's class mapper. [T] needs a no-argument constructor. */
inline fun <reified T : Any> DocumentReference.snapshots(): Flow<T?> =
    snapshots { it.toObject(T::class.java) }

/** [snapshots] that maps with Firestore's class mapper. [T] needs a no-argument constructor. */
inline fun <reified T : Any> Query.snapshots(): Flow<List<T>> =
    snapshots { it.toObject(T::class.java) }
