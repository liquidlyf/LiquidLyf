package io.github.liquidlyf.core.data.geo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GeoModule {
    @Binds
    abstract fun geoRepository(impl: AssetGeoRepository): GeoRepository
}
