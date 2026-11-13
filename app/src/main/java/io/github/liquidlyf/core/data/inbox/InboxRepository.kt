package io.github.liquidlyf.core.data.inbox

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.InboxDoc
import io.github.liquidlyf.core.firebase.dto.inbox
import io.github.liquidlyf.core.firebase.dto.toTimestamp
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The personal inbox, `users/{uid}/notifications`. Items are immutable and carry no text; the
 * app renders them from type + requestId. "Seen" is ProfileRepository.markInboxSeen.
 */
interface InboxRepository {
    /** Newest first, a page at a time. Older pages are plain reads, never a listener. */
    suspend fun page(uid: String, after: InboxItem? = null, limit: Long = PAGE_SIZE): AppResult<List<InboxItem>>

    companion object {
        const val PAGE_SIZE = 30L
    }
}

@Singleton
class FirestoreInboxRepository @Inject constructor(private val db: FirebaseFirestore) : InboxRepository {
    override suspend fun page(uid: String, after: InboxItem?, limit: Long): AppResult<List<InboxItem>> = appResultOf {
        var query = db.inbox(uid).orderBy(InboxDoc.CREATED_AT, Query.Direction.DESCENDING)
        after?.createdAt?.let { query = query.startAfter(it.toTimestamp()) }
        query.limit(limit).get().await().documents.mapNotNull { d -> d.data?.let { InboxDoc.fromMap(d.id, it) } }
    }
}
