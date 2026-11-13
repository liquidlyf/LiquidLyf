package io.github.liquidlyf

import app.cash.turbine.test
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.data.settings.UserSettings
import io.github.liquidlyf.navigation.GateZone
import io.github.liquidlyf.navigation.TopLevelDestination
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeSettingsRepository
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val settings = FakeSettingsRepository()
    private val session = FakeSessionManager(SessionState.Loading)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ready(vm: MainViewModel) = vm.uiState.value as MainUiState.Ready

    @Test
    fun `stays loading until both settings and the session are read`() = runTest {
        val vm = MainViewModel(settings, session)
        assertEquals(MainUiState.Loading, vm.uiState.value)
        settings.emit(UserSettings())
        assertEquals(MainUiState.Loading, vm.uiState.value)
        session.state.value = SessionState.SignedOut
        assertEquals(GateZone.ONBOARDING, ready(vm).startZone)
    }

    @Test
    fun `first run starts with onboarding, later runs at sign-in`() = runTest {
        session.state.value = SessionState.SignedOut
        val firstRun = MainViewModel(settings, session)
        settings.emit(UserSettings(onboardingSeen = false))
        assertEquals(GateZone.ONBOARDING, ready(firstRun).startZone)

        val laterSettings = FakeSettingsRepository(UserSettings(onboardingSeen = true))
        assertEquals(GateZone.SIGNED_OUT, ready(MainViewModel(laterSettings, session)).startZone)
    }

    @Test
    fun `a signed-in user starts where their session is`() = runTest {
        val seen = FakeSettingsRepository(UserSettings(onboardingSeen = true))
        mapOf(
            SessionState.Unverified(TestData.user(verified = false)) to GateZone.VERIFY_EMAIL,
            SessionState.NeedsProfile(TestData.user()) to GateZone.PROFILE_SETUP,
            SessionState.Ready(TestData.user(), TestData.profile()) to GateZone.MAIN,
        ).forEach { (state, zone) ->
            assertEquals(zone, ready(MainViewModel(seen, FakeSessionManager(state))).startZone)
        }
    }

    @Test
    fun `the start is fixed for the launch while the theme and session follow`() = runTest {
        session.state.value = SessionState.SignedOut
        val vm = MainViewModel(settings, session)

        vm.uiState.test {
            assertEquals(MainUiState.Loading, awaitItem())
            settings.emit(UserSettings(onboardingSeen = false, lastSelectedTab = "DONORS"))
            assertEquals(
                MainUiState.Ready(ThemeMode.SYSTEM, GateZone.ONBOARDING, TopLevelDestination.DONORS, SessionState.SignedOut),
                awaitItem(),
            )
            settings.emit(UserSettings(themeMode = ThemeMode.DARK, onboardingSeen = true))
            assertEquals(GateZone.ONBOARDING, (awaitItem() as MainUiState.Ready).startZone)
            val signedIn = SessionState.Ready(TestData.user(), TestData.profile())
            session.state.value = signedIn
            val after = awaitItem() as MainUiState.Ready
            assertEquals(signedIn, after.session)
            assertEquals(ThemeMode.DARK, after.themeMode)
            assertEquals(GateZone.ONBOARDING, after.startZone)
        }
    }

    @Test
    fun `an unknown saved tab is ignored`() = runTest {
        session.state.value = SessionState.SignedOut
        val vm = MainViewModel(settings, session)
        settings.emit(UserSettings(lastSelectedTab = "SEARCH"))
        assertEquals(null, ready(vm).initialTab)
    }

    @Test
    fun `events reach settings and the session`() = runTest {
        val vm = MainViewModel(settings, session)
        vm.onOnboardingFinished()
        vm.onTabSelected(TopLevelDestination.REQUESTS)
        vm.retrySession()
        vm.signOut()

        assertEquals(true, settings.onboardingSeen)
        assertEquals("REQUESTS", settings.lastSelectedTab)
        assertEquals(1, session.refreshes)
        assertEquals(1, session.signOuts)
    }
}
