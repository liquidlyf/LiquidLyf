package io.github.liquidlyf.feature.settings

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.AppLanguageStore
import io.github.liquidlyf.core.data.settings.UserSettings
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeSettingsRepository
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val me = TestData.profile(uid = "me") // listed, available, B+
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val profiles = FakeProfileRepository().apply { profiles["me"] = me }

    private class FakeLanguages(var chosen: AppLanguage = AppLanguage.SYSTEM) : AppLanguageStore {
        val sets = mutableListOf<AppLanguage>()
        override fun current() = chosen
        override fun set(language: AppLanguage) {
            sets += language
            chosen = language
        }
    }

    private val languages = FakeLanguages()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() = SettingsViewModel(FakeSettingsRepository(UserSettings()), FakeAuthRepository(), languages, session, profiles)
        .also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } }

    /** Like the real session: refresh() reads the profile the fake repository now holds. */
    private fun refreshFromRepository() {
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), profiles.profiles.getValue("me"))
    }

    @Test
    fun `the language starts as chosen and a new choice is applied once`() = runTest(dispatcher) {
        languages.chosen = AppLanguage.BANGLA
        val vm = vm()
        advanceUntilIdle()
        assertEquals(AppLanguage.BANGLA, vm.uiState.value.language)

        vm.setLanguage(AppLanguage.BANGLA) // already chosen: nothing restarts
        vm.setLanguage(AppLanguage.SYSTEM)
        advanceUntilIdle()
        assertEquals(listOf(AppLanguage.SYSTEM), languages.sets)
        assertEquals(AppLanguage.SYSTEM, vm.uiState.value.language)
    }

    @Test
    fun `the privacy switches show the profile, and unlisting and relisting are saved`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.listed)
        assertEquals(true, vm.uiState.value.available)

        vm.setListed(false)
        advanceUntilIdle()
        assertEquals(1, session.refreshes)
        refreshFromRepository()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.listed)

        vm.setListed(true)
        advanceUntilIdle()
        vm.setAvailable(false)
        advanceUntilIdle()
        assertEquals(listOf(false, true), profiles.listingWrites)
        assertEquals(listOf(false), profiles.availabilityWrites)
    }

    @Test
    fun `before the profile is read the switches stay hidden`() = runTest(dispatcher) {
        session.state.value = SessionState.Loading
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.listed)
        vm.setListed(true) // no profile: nothing is written
        advanceUntilIdle()
        assertEquals(emptyList<Boolean>(), profiles.listingWrites)
    }

    @Test
    fun `a failed write says why`() = runTest(dispatcher) {
        profiles.failNext = AppError.Network
        val vm = vm()
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()
        vm.setListed(false)
        advanceUntilIdle()
        assertEquals(listOf(SettingsEvent.Message(R.string.error_network)), events)
        assertEquals(false, vm.uiState.value.saving)
    }
}
