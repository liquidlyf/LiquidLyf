package io.github.liquidlyf

import app.cash.turbine.test
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.data.settings.UserSettings
import io.github.liquidlyf.navigation.TopLevelDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val settingsRepository = FakeSettingsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `stays loading until settings are read`() = runTest {
        val viewModel = MainViewModel(settingsRepository)

        assertEquals(MainUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `first run starts with onboarding, later runs do not`() = runTest {
        val firstRun = MainViewModel(settingsRepository)
        settingsRepository.emit(UserSettings(onboardingSeen = false))
        assertEquals(true, (firstRun.uiState.value as MainUiState.Ready).startWithOnboarding)

        val laterRepository = FakeSettingsRepository()
        val laterRun = MainViewModel(laterRepository)
        laterRepository.emit(UserSettings(onboardingSeen = true))
        assertEquals(false, (laterRun.uiState.value as MainUiState.Ready).startWithOnboarding)
    }

    @Test
    fun `start destination is fixed for the launch while the theme follows settings`() = runTest {
        val viewModel = MainViewModel(settingsRepository)

        viewModel.uiState.test {
            assertEquals(MainUiState.Loading, awaitItem())

            settingsRepository.emit(UserSettings(onboardingSeen = false, lastSelectedTab = "DONORS"))
            assertEquals(
                MainUiState.Ready(ThemeMode.SYSTEM, startWithOnboarding = true, initialTab = TopLevelDestination.DONORS),
                awaitItem(),
            )

            // Finishing onboarding and changing the theme must not move the start destination.
            settingsRepository.emit(UserSettings(themeMode = ThemeMode.DARK, onboardingSeen = true))
            assertEquals(
                MainUiState.Ready(ThemeMode.DARK, startWithOnboarding = true, initialTab = TopLevelDestination.DONORS),
                awaitItem(),
            )
        }
    }

    @Test
    fun `an unknown saved tab is ignored`() = runTest {
        val viewModel = MainViewModel(settingsRepository)
        settingsRepository.emit(UserSettings(lastSelectedTab = "SEARCH"))

        assertEquals(null, (viewModel.uiState.value as MainUiState.Ready).initialTab)
    }

    @Test
    fun `events are saved to settings`() = runTest {
        val viewModel = MainViewModel(settingsRepository)

        viewModel.onOnboardingFinished()
        viewModel.onTabSelected(TopLevelDestination.REQUESTS)

        assertEquals(true, settingsRepository.onboardingSeen)
        assertEquals("REQUESTS", settingsRepository.lastSelectedTab)
    }
}

private class FakeSettingsRepository : SettingsRepository {
    private val flow = MutableSharedFlow<UserSettings>(replay = 1)
    override val settings: SharedFlow<UserSettings> = flow

    var onboardingSeen: Boolean? = null
    var lastSelectedTab: String? = null

    suspend fun emit(value: UserSettings) = flow.emit(value)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit

    override suspend fun setOnboardingSeen(seen: Boolean) {
        onboardingSeen = seen
    }

    override suspend fun setLastSelectedTab(tab: String) {
        lastSelectedTab = tab
    }
}
