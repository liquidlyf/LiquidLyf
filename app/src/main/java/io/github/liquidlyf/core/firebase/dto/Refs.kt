package io.github.liquidlyf.core.firebase.dto

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/* Document paths, in one place (doc 07 §2). */

fun FirebaseFirestore.userRef(uid: String): DocumentReference = collection(UserDoc.COLLECTION).document(uid)

fun FirebaseFirestore.donorRef(uid: String): DocumentReference = collection(DonorDoc.COLLECTION).document(uid)

fun FirebaseFirestore.statsRef(uid: String): DocumentReference = collection(UserStatsDoc.COLLECTION).document(uid)

fun FirebaseFirestore.requests(): CollectionReference = collection(RequestDoc.COLLECTION)

fun FirebaseFirestore.requestRef(id: String): DocumentReference = requests().document(id)

fun FirebaseFirestore.contactRef(requestId: String): DocumentReference =
    requestRef(requestId).collection(ContactDoc.COLLECTION).document(ContactDoc.DOC_ID)

fun FirebaseFirestore.responses(requestId: String): CollectionReference =
    requestRef(requestId).collection(ResponseDoc.COLLECTION)

fun FirebaseFirestore.responseRef(requestId: String, donorUid: String): DocumentReference =
    responses(requestId).document(donorUid)

fun FirebaseFirestore.inbox(uid: String): CollectionReference = userRef(uid).collection(InboxDoc.COLLECTION)

fun FirebaseFirestore.donations(uid: String): CollectionReference = userRef(uid).collection(DonationDoc.COLLECTION)

fun FirebaseFirestore.reportRef(id: String): DocumentReference = collection(ReportDoc.COLLECTION).document(id)
