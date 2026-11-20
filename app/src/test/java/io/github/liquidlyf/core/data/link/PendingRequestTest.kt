package io.github.liquidlyf.core.data.link

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** D31: the request link, and the request kept on the phone until the person is in the app. */
class PendingRequestTest {
    /** In memory: DataStore's file can't be replaced on Windows in a PC test. The phone tests use the real one. */
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val id = "AbCdEfGhIjKlMnOpQrSt" // 20 letters and digits, like a Firestore ID

    @Test
    fun `the share link carries the ID after the hash`() {
        assertEquals("https://liquidlyf.github.io/r/#$id", RequestLinks.of(id))
        assertEquals(id, RequestLinks.idFrom(RequestLinks.of(id)))
    }

    @Test
    fun `the page's other forms work, anything else is ignored`() {
        assertEquals(id, RequestLinks.idFrom("https://liquidlyf.github.io/r/?id=$id"))
        assertEquals(id, RequestLinks.idFrom("https://liquidlyf.github.io/r#$id"))
        assertEquals(id, RequestLinks.idFrom("https://liquidlyf.github.io/r/index.html#$id"))
        assertEquals(id, RequestLinks.idFrom("https://liquidlyf.github.io/r/?utm=x&id=$id"))
        assertNull(RequestLinks.idFrom("http://liquidlyf.github.io/r/#$id"))
        assertNull(RequestLinks.idFrom("https://liquidlyf.example/r/#$id"))
        assertNull(RequestLinks.idFrom("https://liquidlyf.github.io/privacy#$id"))
        assertNull(RequestLinks.idFrom("https://liquidlyf.github.io/r/#short"))
        assertNull(RequestLinks.idFrom("https://liquidlyf.github.io/r/#${id}x"))
        assertNull(RequestLinks.idFrom("https://liquidlyf.github.io/r/#../../users/x"))
        assertNull(RequestLinks.idFrom("not a link at all"))
        assertNull(RequestLinks.idFrom(null))
    }

    @Test
    fun `the page's button and the alerts use the app's own link`() {
        assertEquals(id, RequestLinks.idFromAny("liquidlyf://request/$id"))
        assertEquals(id, RequestLinks.idFromAny(RequestLinks.of(id)))
        assertNull(RequestLinks.idFromAny("liquidlyf://moderation"))
        assertNull(RequestLinks.idFromAny("liquidlyf://request/short"))
    }

    @Test
    fun `kept until taken, once, and the newest link wins`() = runTest {
        var now = TestData.NOW
        val store = DataStorePendingRequestStore({ MemoryPreferences() }, Clock { now })
        assertNull(store.pending.first())
        store.keep(id)
        store.keep("ZyXwVuTsRqPoNmLkJiHg")
        assertEquals("ZyXwVuTsRqPoNmLkJiHg", store.pending.first())
        now = now.plus(Duration.ofHours(70))
        assertEquals("ZyXwVuTsRqPoNmLkJiHg", store.take())
        assertNull(store.take())
        assertNull(store.pending.first())
    }

    @Test
    fun `older than 3 days it's dropped`() = runTest {
        var now = TestData.NOW
        val store = DataStorePendingRequestStore({ MemoryPreferences() }, Clock { now })
        store.keep(id)
        now = now.plus(Duration.ofDays(3))
        assertNull(store.pending.first())
        // Deleted at the next start, not only ignored.
        store.dropIfOld()
        assertNull(store.take())
        // A link tapped again later is kept afresh.
        store.keep(id)
        assertEquals(id, store.take())
    }

    @Test
    fun `fresh means under 3 days`() {
        val t = Instant.parse("2026-11-20T10:00:00Z")
        assertEquals(true, PendingRequestStore.fresh(t, t.plus(Duration.ofDays(3)).minusSeconds(1)))
        assertEquals(false, PendingRequestStore.fresh(t, t.plus(Duration.ofDays(3))))
    }
}
