package io.github.liquidlyf.core.data.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import javax.inject.Inject

/** Phase 8.1: the app's own language. SYSTEM follows the phone. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    BANGLA("bn"),
}

/** Reads and sets the per-app language. A wrapper, so the view model can be tested without Android. */
interface AppLanguageStore {
    fun current(): AppLanguage

    /** Restarts the screens in the new language. */
    fun set(language: AppLanguage)
}

/**
 * AppCompat's per-app locales: Android 13+ keeps them in the phone's app settings
 * (`locales_config.xml`); older versions use the `autoStoreLocales` service in the manifest.
 */
class AppCompatLanguageStore @Inject constructor() : AppLanguageStore {
    override fun current(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
        return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag == tag } ?: AppLanguage.SYSTEM
    }

    override fun set(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag),
        )
    }
}
