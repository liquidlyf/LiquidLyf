package io.github.liquidlyf.core.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/*
 * Firestore listeners as Flows. The listener is removed as soon as the collector stops
 * (screen left, ViewModel cleared), so listeners can no longer pile up (BUG-008).
 * Errors close the flow; repositories turn them into AppError with toAppError().
 */

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
