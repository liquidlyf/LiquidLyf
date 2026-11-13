package io.github.liquidlyf.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.concurrent.ConcurrentHashMap

/**
 * One DataStore per file for the whole process. DataStore throws if two instances open the same
 * file, and a Hilt component can be built more than once per process (every instrumented test
 * builds a new one), so a @Singleton alone isn't enough.
 */
object ProcessDataStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(context: Context, name: String): DataStore<Preferences> =
        stores.computeIfAbsent(name) {
            PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(name) })
        }
}
