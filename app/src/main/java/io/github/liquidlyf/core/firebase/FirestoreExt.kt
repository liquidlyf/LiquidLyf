package io.github.liquidlyf.core.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

/** [snapshots] that maps with Firestore's class mapper. [T] needs a no-argument constructor. */
inline fun <reified T : Any> DocumentReference.snapshots(): Flow<T?> =
    snapshots { it.toObject(T::class.java) }

/** [snapshots] that maps with Firestore's class mapper. [T] needs a no-argument constructor. */
inline fun <reified T : Any> Query.snapshots(): Flow<List<T>> =
    snapshots { it.toObject(T::class.java) }
