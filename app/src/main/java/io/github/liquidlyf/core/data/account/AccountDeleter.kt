package io.github.liquidlyf.core.data.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.ProcessDataStores
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.request.FirestoreRequestRepository
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.ResponseDoc
import io.github.liquidlyf.core.firebase.dto.Responders
import io.github.liquidlyf.core.firebase.dto.contactRef
import io.github.liquidlyf.core.firebase.dto.donations
import io.github.liquidlyf.core.firebase.dto.donorRef
import io.github.liquidlyf.core.firebase.dto.inbox
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.requests
import io.github.liquidlyf.core.firebase.dto.responseRef
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.firebase.dto.userRef
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Which part of the deletion is done; the screen shows it. */
enum class DeletionStep { OFFERS_WITHDRAWN, REQUESTS_ANONYMIZED, OFFERS_ANONYMIZED, PRIVATE_DATA_DELETED, PROFILE_DELETED }

/**
 * In-app account deletion (doc 06 §4.7, BUG-041), with no server code: write contract item 16 in
 * firebase/README.md, one rules-checked batch at a time. Requests and offers stay for the people
 * involved, but read "Deleted user" and lose the phone; everything else of the user is deleted;
 * the Firebase Auth user goes last.
 *
 * [start] remembers the deletion on this phone, so a killed app resumes it on the next launch.
 * Every run re-reads the server, so a step that already happened is never sent twice (the rules
 * refuse a second userStats −1 or a second withdraw).
 */
interface AccountDeleter {
    suspend fun start(uid: String)

    /** True while a deletion of [uid] is unfinished on this phone. */
    suspend fun isPending(uid: String): Boolean

    /** Runs or resumes the deletion. Re-authenticate first: the last step needs a recent sign-in. */
    suspend fun run(uid: String, onProgress: (DeletionStep) -> Unit = {}): AppResult<Unit>
}

@Singleton
class FirestoreAccountDeleter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val push: PushTopicManager,
) : AccountDeleter {

    private val store by lazy { ProcessDataStores.get(context, "account_deletion") }

    override suspend fun start(uid: String) {
        store.edit { it[PENDING_UID] = uid }
    }

    override suspend fun isPending(uid: String): Boolean = store.data.first()[PENDING_UID] == uid

    override suspend fun run(uid: String, onProgress: (DeletionStep) -> Unit): AppResult<Unit> {
        val result = appResultOf {
            // 1. Withdraw offers that still hold a place (anonymizing keeps the status).
            for (offer in myOffers(uid).filter { it.status == ResponseStatus.OFFERED || it.status == ResponseStatus.CONFIRMED }) {
                val batch = db.batch()
                batch.update(db.responseRef(offer.requestId, uid), ResponseDoc.statusMap(ResponseStatus.WITHDRAWN))
                batch.update(
                    db.requestRef(offer.requestId),
                    mapOf(
                        "${Responders.path(uid)}.${Responders.STATUS}" to ResponseStatus.WITHDRAWN.code,
                        RequestDoc.LAST_RESPONDER_UID to uid,
                        RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                )
                batch.commit().await()
            }
            onProgress(DeletionStep.OFFERS_WITHDRAWN)

            // 2. My requests: anonymized; an OPEN one is cancelled with its userStats −1 (one per batch).
            val requests = myRequests(uid)
            for (request in requests) {
                val open = request.status == RequestStatus.OPEN
                if (request.requesterDisplayName == RequestDoc.DELETED_NAME && !open) continue
                val fields = mutableMapOf<String, Any>(
                    RequestDoc.REQUESTER_DISPLAY_NAME to RequestDoc.DELETED_NAME,
                    RequestDoc.NOTES to FieldValue.delete(),
                    RequestDoc.CLOSED_REASON to FieldValue.delete(),
                    RequestDoc.FLAGGED to false,
                    RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                )
                val batch = db.batch()
                if (open) {
                    fields[RequestDoc.STATUS] = RequestStatus.CANCELLED.code
                    batch.update(db.statsRef(uid), FirestoreRequestRepository.openRequestsDown(request.id))
                }
                batch.update(db.requestRef(request.id), fields)
                batch.commit().await()
            }
            onProgress(DeletionStep.REQUESTS_ANONYMIZED)

            // 3. My offers: name and phone gone from both the response and the request's summary.
            for (offer in myOffers(uid)) {
                val request = db.requestRef(offer.requestId).get(Source.SERVER).await()
                val entry = request.data?.let { RequestDoc.fromMap(request.id, it) }?.responders?.get(uid) ?: continue
                val batch = db.batch()
                batch.update(
                    db.requestRef(offer.requestId),
                    mapOf(
                        Responders.path(uid) to Responders.entry(Responder(RequestDoc.DELETED_NAME, entry.group, entry.status)),
                        RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                )
                batch.update(
                    db.responseRef(offer.requestId, uid),
                    mapOf(
                        ResponseDoc.DONOR_DISPLAY_NAME to RequestDoc.DELETED_NAME,
                        ResponseDoc.DONOR_PHONE to FieldValue.delete(),
                        ResponseDoc.MESSAGE to FieldValue.delete(),
                        ResponseDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                )
                batch.commit().await()
            }
            onProgress(DeletionStep.OFFERS_ANONYMIZED)

            // 4. Private contacts of my requests, and my inbox.
            val refs = requests.map { db.contactRef(it.id) } + all(db.inbox(uid)).map { it.reference }
            refs.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach(batch::delete)
                batch.commit().await()
            }
            onProgress(DeletionStep.PRIVATE_DATA_DELETED)

            // 5. The profile, its card, its counters and the donation history, together.
            if (db.userRef(uid).get(Source.SERVER).await().exists()) {
                val batch = db.batch()
                batch.delete(db.donorRef(uid))
                batch.delete(db.statsRef(uid))
                all(db.donations(uid)).forEach { batch.delete(it.reference) }
                batch.delete(db.userRef(uid))
                batch.commit().await()
            }
            onProgress(DeletionStep.PROFILE_DELETED)

            // 6. No more alerts on this phone, then the account itself, last.
            push.sync(null)
        }
        if (result is AppResult.Failure) return result
        return when (val deleted = auth.deleteUser()) {
            is AppResult.Success -> {
                store.edit { it.remove(PENDING_UID) }
                deleted
            }
            is AppResult.Failure -> deleted
        }
    }

    private suspend fun myOffers(uid: String): List<DonorResponse> =
        // With the orderBy, the query uses the (donorUid, createdAt) collection-group index; production
        // has no single-field index for collection groups.
        all(
            db.collectionGroup(ResponseDoc.COLLECTION)
                .whereEqualTo(ResponseDoc.DONOR_UID, uid)
                .orderBy(ResponseDoc.CREATED_AT, Query.Direction.DESCENDING),
        )
            .mapNotNull { d -> d.data?.let(ResponseDoc::fromMap) }

    private suspend fun myRequests(uid: String): List<BloodRequest> =
        all(db.requests().whereEqualTo(RequestDoc.REQUESTER_UID, uid))
            .mapNotNull { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }

    /** Every document of [query], from the server, a page at a time. */
    private suspend fun all(query: Query): List<DocumentSnapshot> {
        val out = mutableListOf<DocumentSnapshot>()
        var page = query.limit(PAGE).get(Source.SERVER).await().documents
        while (page.isNotEmpty()) {
            out += page
            if (page.size < PAGE) break
            page = query.startAfter(page.last()).limit(PAGE).get(Source.SERVER).await().documents
        }
        return out
    }

    private companion object {
        val PENDING_UID = stringPreferencesKey("pending_uid")
        const val PAGE = 100L
        const val BATCH_LIMIT = 400
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds abstract fun deleter(impl: FirestoreAccountDeleter): AccountDeleter
}
