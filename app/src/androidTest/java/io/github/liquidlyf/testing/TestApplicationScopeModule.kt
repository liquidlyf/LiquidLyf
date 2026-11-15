package io.github.liquidlyf.testing

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.ApplicationScopeModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Singleton

/**
 * Each Hilt test gets new singletons, but the app-wide scope of the test before was never
 * cancelled, so the old session managers kept reacting to the next test's sign-ins (two extra
 * topic syncs and refused profile writes per sign-in). In a long run that stalled
 * RequestLoopTest for minutes. Here a test's scope cancels the previous test's when it's made.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ApplicationScopeModule::class])
object TestApplicationScopeModule {
    private var previous: CoroutineScope? = null

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = synchronized(this) {
        previous?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { previous = it }
    }
}
