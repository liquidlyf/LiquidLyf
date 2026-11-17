package io.github.liquidlyf.core.firebase

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** A32: what reaches Crashlytics. */
class CrashReportsTest {
    private val keys = mutableMapOf<String, String>()
    private val recorded = mutableListOf<Throwable>()

    @Before
    fun install() = CrashReports.install(
        object : CrashReports.Sink {
            override fun setKey(key: String, value: String) {
                keys[key] = value
            }

            override fun record(error: Throwable) {
                recorded += error
            }
        },
    )

    @After
    fun uninstall() = CrashReports.install(null)

    @Test
    fun `an unexpected error is reported with its types and stacks, never its messages`() = runTest {
        val path = "users/uid-123/private/doc"
        val inner = FirebaseFirestoreException("can't read $path", Code.INTERNAL)
        val outer = IllegalStateException("while saving $path", inner)
        assertEquals(AppResult.Failure(AppError.Unknown), appResultOf { throw outer })

        val chain = generateSequence(recorded.single()) { it.cause }.toList()
        assertEquals(
            listOf(
                "java.lang.IllegalStateException",
                "java.lang.IllegalStateException",
                "com.google.firebase.firestore.FirebaseFirestoreException INTERNAL",
            ),
            chain.map { it.message },
        )
        chain.forEach { assertFalse(it.toString(), it.toString().contains("uid-123")) }
        // The report is "thrown" where the error was caught (here), so reports group by place;
        // the original stacks come along as its causes.
        assertTrue(chain[0].stackTrace.first().className.startsWith(CrashReportsTest::class.java.name))
        assertArrayEquals(outer.stackTrace, chain[1].stackTrace)
        assertArrayEquals(inner.stackTrace, chain[2].stackTrace)
    }

    @Test
    fun `errors the app has a message for aren't reported`() = runTest {
        appResultOf { throw IOException("offline") }
        appResultOf { throw FirebaseFirestoreException("x", Code.PERMISSION_DENIED) }
        assertEquals(AppError.NotFound, CrashReports.appError(FirebaseFirestoreException("x", Code.NOT_FOUND)))
        assertEquals(emptyList<Throwable>(), recorded)
    }

    @Test
    fun `without Crashlytics (demo, dev, tests) nothing is sent and nothing breaks`() {
        CrashReports.install(null)
        assertEquals(AppError.Unknown, CrashReports.appError(IllegalStateException("x")))
        CrashReports.screen("Home")
        assertEquals(emptyList<Throwable>(), recorded)
        assertEquals(emptyMap<String, String>(), keys)
    }

    @Test
    fun `the screen key is the route's name, never its arguments`() {
        assertEquals("RequestDetail", CrashReports.screenName("io.github.liquidlyf.navigation.RequestDetail/{id}"))
        assertEquals("FindDonors", CrashReports.screenName("io.github.liquidlyf.navigation.FindDonors?group={group}"))
        assertEquals("Home", CrashReports.screenName("io.github.liquidlyf.navigation.Home"))
        CrashReports.screen("Home")
        assertEquals(mapOf(CrashReports.KEY_SCREEN to "Home"), keys)
    }

    @Test
    fun `a long or looping cause chain is cut short`() {
        val looping = object : Exception("a") {
            override val cause: Throwable get() = this
        }
        CrashReports.appError(looping)
        var deep: Throwable = IllegalStateException("0")
        repeat(20) { deep = IllegalStateException("$it", deep) }
        CrashReports.appError(deep)
        assertEquals(2, generateSequence(recorded[0]) { it.cause }.count())
        assertEquals(6, generateSequence(recorded[1]) { it.cause }.count())
    }
}
