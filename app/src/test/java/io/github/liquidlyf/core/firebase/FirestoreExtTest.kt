package io.github.liquidlyf.core.firebase

import app.cash.turbine.test
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-008: Firestore listeners must be removed when the collector goes away. */
class FirestoreExtTest {

    private val registration = mockk<ListenerRegistration>(relaxed = true)

    private fun document(name: String?, exists: Boolean = true) = mockk<DocumentSnapshot> {
        every { this@mockk.exists() } returns exists
        every { getString("name") } returns name
    }

    @Test
    fun `document flow emits mapped values and removes the listener on cancel`() = runTest {
        lateinit var listener: EventListener<DocumentSnapshot>
        val ref = mockk<DocumentReference> {
            every { addSnapshotListener(any<EventListener<DocumentSnapshot>>()) } answers {
                listener = firstArg()
                registration
            }
        }

        ref.snapshots { it.getString("name") }.test {
            listener.onEvent(document("Rahim"), null)
            assertEquals("Rahim", awaitItem())
            listener.onEvent(document(null, exists = false), null)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { registration.remove() }
    }

    @Test
    fun `document flow fails on a listener error and still removes the listener`() = runTest {
        lateinit var listener: EventListener<DocumentSnapshot>
        val ref = mockk<DocumentReference> {
            every { addSnapshotListener(any<EventListener<DocumentSnapshot>>()) } answers {
                listener = firstArg()
                registration
            }
        }
        val failure = FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        ref.snapshots { it.getString("name") }.test {
            listener.onEvent(null, failure)
            assertTrue(awaitError() is FirebaseFirestoreException)
        }

        verify(exactly = 1) { registration.remove() }
    }

    @Test
    fun `query flow maps documents, skips rejected ones and removes the listener`() = runTest {
        lateinit var listener: EventListener<QuerySnapshot>
        val query = mockk<Query> {
            every { addSnapshotListener(any<EventListener<QuerySnapshot>>()) } answers {
                listener = firstArg()
                registration
            }
        }
        val snapshot = mockk<QuerySnapshot> {
            every { documents } returns listOf(document("A"), document(null), document("B"))
        }

        query.snapshots { it.getString("name") }.test {
            listener.onEvent(snapshot, null)
            assertEquals(listOf("A", "B"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { registration.remove() }
    }
}
