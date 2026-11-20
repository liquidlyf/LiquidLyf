package io.github.liquidlyf.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
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
class AppCompatLanguageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppLanguageStore {
    override fun current(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
        return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag == tag } ?: AppLanguage.SYSTEM
    }

    override fun set(language: AppLanguage) {
        OffScreenLanguage.remember(context, language.tag)
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag),
        )
    }
}

/**
 * D's catch (20 Nov): Android 12 and older apply AppCompat's per-app language to screens only, so
 * notifications, built off screen (pushes, reminders), came in the phone's language: English for a
 * Bangla app on an English phone. The choice is kept here too, where code with no screen can read
 * it, even in a process a push started; [wrap] gives a context in it. Android 13+ applies the app's
 * language to the whole app itself.
 */
object OffScreenLanguage {
    private const val FILE = "app_language"
    private const val TAG = "tag"

    /** Keeps [tag] ("" = the phone's language); true if it changed. */
    fun remember(context: Context, tag: String): Boolean {
        val prefs = prefs(context)
        if (prefs.getString(TAG, "") == tag) return false
        prefs.edit { putString(TAG, tag) }
        return true
    }

    /** [context] in the app's own language, for words shown outside the app's screens. */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = prefs(context).getString(TAG, "").orEmpty()
        if (tag.isEmpty()) return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
