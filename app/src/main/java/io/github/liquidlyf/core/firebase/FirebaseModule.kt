package io.github.liquidlyf.core.firebase

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.BuildConfig
import javax.inject.Singleton

/**
 * The only place that creates Firebase clients. Repositories get them injected; composables
 * never touch Firebase. In the demo flavor every client points at the local emulators, set
 * here before the first call.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    private const val AUTH_EMULATOR_PORT = 9099
    // 8085 instead of the default 8080, which other software often holds.
    // Must match the firestore port in firebase/firebase.json.
    private const val FIRESTORE_EMULATOR_PORT = 8085

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = Firebase.auth.apply {
        if (BuildConfig.USE_FIREBASE_EMULATOR) useEmulator(BuildConfig.EMULATOR_HOST, AUTH_EMULATOR_PORT)
    }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore.apply {
        if (BuildConfig.USE_FIREBASE_EMULATOR) useEmulator(BuildConfig.EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
    }
}
