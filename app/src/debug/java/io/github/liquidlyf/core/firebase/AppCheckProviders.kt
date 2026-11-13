package io.github.liquidlyf.core.firebase

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds: the App Check debug provider. It logs a token to register in the Firebase console. */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
