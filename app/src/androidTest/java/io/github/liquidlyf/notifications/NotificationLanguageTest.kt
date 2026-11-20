package io.github.liquidlyf.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.AppLanguageStore
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Urgency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import javax.inject.Inject

/**
 * D's catch (20 Nov): the app's own language is AppCompat's per-app setting, which Android 12 and
 * older apply to screens only. Alerts and reminders are built off screen, so with the phone in
 * English and the app in বাংলা they must still come in Bangla (and the other way round).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotificationLanguageTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var languages: AppLanguageStore

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var before = AppLanguage.SYSTEM

    @Before
    fun setUp() {
        hilt.inject()
        assumeTrue("Notifications are off for the app on this phone", manager.areNotificationsEnabled())
        before = languages.current()
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        if (!::languages.isInitialized) return
        instrumentation.runOnMainSync { languages.set(before) }
        manager.cancelAll()
    }

    private fun inLanguage(tag: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun title(id: Int): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.activeNotifications.none { it.id == id } && System.currentTimeMillis() < deadline) Thread.sleep(100)
        return manager.activeNotifications.first { it.id == id }.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    }

    private fun check(language: AppLanguage) = runBlocking {
        instrumentation.runOnMainSync { languages.set(language) }
        val words = inLanguage(language.tag)
        notifier.showEligibleAgain()
        assertEquals(words.getString(R.string.notification_eligible_again_title), title(ELIGIBLE_AGAIN_NOTIFICATION_ID))
        notifier.showRequestAlert(PushMessage.RequestAlert("lang-test", BloodGroup.B_POS, null, Urgency.URGENT, 1), stillNeeded = false)
        val group = words.getString(BloodGroup.B_POS.labelRes)
        assertEquals(words.getString(R.string.notification_new_request_title, group), title("lang-test:alert".hashCode()))
    }

    /** The phone's language is the other one, so the app's own choice is what's being tested. */
    @Test
    fun aBanglaAppSendsBanglaNotifications() {
        assumeTrue("needs the phone in English", context.resources.configuration.locales[0].language != "bn")
        check(AppLanguage.BANGLA)
    }

    @Test
    fun anEnglishAppSendsEnglishNotifications() {
        assumeTrue("needs the phone in Bangla", context.resources.configuration.locales[0].language == "bn")
        check(AppLanguage.ENGLISH)
    }
}
