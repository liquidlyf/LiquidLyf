package io.github.liquidlyf.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.donor.DonorRepository
import io.github.liquidlyf.core.data.donor.FirestoreDonorRepository
import io.github.liquidlyf.core.data.inbox.FirestoreInboxRepository
import io.github.liquidlyf.core.data.inbox.InboxRepository
import io.github.liquidlyf.core.data.profile.FirestoreProfileRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.FirestoreOfferRepository
import io.github.liquidlyf.core.data.request.FirestoreRequestRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.FirestoreSafetyRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.util.Clock
import javax.inject.Singleton

/**
 * Repositories and their helpers. Firebase clients (and the demo flavor's emulator hosts,
 * 127.0.0.1 through `adb reverse`) come from core/firebase/FirebaseModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun profile(impl: FirestoreProfileRepository): ProfileRepository

    @Binds abstract fun requests(impl: FirestoreRequestRepository): RequestRepository

    @Binds abstract fun offers(impl: FirestoreOfferRepository): OfferRepository

    @Binds abstract fun inbox(impl: FirestoreInboxRepository): InboxRepository

    @Binds abstract fun safety(impl: FirestoreSafetyRepository): SafetyRepository

    @Binds abstract fun donors(impl: FirestoreDonorRepository): DonorRepository

    companion object {
        @Provides
        @Singleton
        fun clock(): Clock = Clock.SYSTEM

        /** Default rules for now; Phase 3 feeds Remote Config values in. */
        @Provides
        @Singleton
        fun eligibility(): EligibilityCalculator = EligibilityCalculator()
    }
}
