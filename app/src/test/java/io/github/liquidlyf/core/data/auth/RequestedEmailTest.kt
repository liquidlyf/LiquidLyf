package io.github.liquidlyf.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/** D34: the asked-for address on the phone: kept, replaced by a newer one, cleared, and gone after 3 days. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestedEmailTest {
    /** In memory: DataStore's file can't be replaced on Windows in a PC test. */
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `kept, the newest wins, cleared`() = runTest {
        val store = DataStoreRequestedEmailStore({ MemoryPreferences() }, Clock { TestData.NOW }, backgroundScope)
        store.keep("first@example.test")
        store.keep("second@example.test")
        runCurrent()
        assertEquals(RequestedEmail("second@example.test", TestData.NOW), store.requested.value)
        store.clear()
        runCurrent()
        assertNull(store.requested.value)
    }

    @Test
    fun `3 days after the request it's not used, and the next start deletes it`() = runTest {
        var now = TestData.NOW
        val store = DataStoreRequestedEmailStore({ MemoryPreferences() }, Clock { now }, backgroundScope)
        store.keep("new@example.test")
        runCurrent()
        now = now.plus(Duration.ofDays(3)).minusSeconds(1)
        assertEquals(true, store.requested.value!!.freshAt(now))
        store.dropIfOld()
        runCurrent()
        assertEquals("new@example.test", store.requested.value?.email)
        now = now.plusSeconds(1)
        assertEquals(false, store.requested.value!!.freshAt(now))
        store.dropIfOld()
        runCurrent()
        assertNull(store.requested.value)
    }
}
