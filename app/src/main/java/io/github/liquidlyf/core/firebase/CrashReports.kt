package io.github.liquidlyf.core.firebase

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import io.github.liquidlyf.core.util.AppError

/**
 * Phase 9.4 (A32, 17 Nov 2026): what Crashlytics gets besides crashes.
 * - Custom keys: the screen (its route's name, never an argument such as a request ID) and the
 *   app version. No "role": everyone can be both donor and requester.
 * - A non-fatal report for every [AppError.Unknown]: the error the app has no message for. It
 *   carries the exception types and stacks only, never their messages, which can hold document
 *   paths with user IDs.
 *
 * LiquidLyfApp installs a [Sink] in builds that send reports (prod). Elsewhere, and in tests,
 * nothing is sent.
 */
object CrashReports {
    interface Sink {
        fun setKey(key: String, value: String)

        fun record(error: Throwable)
    }

    const val KEY_SCREEN = "screen"
    const val KEY_APP_VERSION = "appVersion"

    @Volatile private var sink: Sink? = null

    fun install(sink: Sink?) {
        this.sink = sink
    }

    fun screen(name: String) {
        sink?.setKey(KEY_SCREEN, name)
    }

    /** [error] as an [AppError]; an [AppError.Unknown] is reported too. Use it wherever a repository catches. */
    fun appError(error: Throwable): AppError =
        error.toAppError().also { if (it == AppError.Unknown) unexpected(error) }

    private fun unexpected(error: Throwable) {
        val sink = sink ?: return
        sink.record(scrubbed(error))
    }

    /**
     * An [UnexpectedError] thrown "here" (the repository that caught [error], so reports group by
     * where it happened), caused by [error]'s chain with each message replaced by its type.
     */
    internal fun scrubbed(error: Throwable): UnexpectedError {
        // Runtime names, so this also works after R8 renames the classes.
        val mine = CrashReports::class.java.name
        val here = Throwable().stackTrace.dropWhile { it.className == mine }.toTypedArray()
        return UnexpectedError(describe(error)).apply {
            stackTrace = here
            initCause(chain(error, depth = 0))
        }
    }

    private fun chain(error: Throwable, depth: Int): UnexpectedError = UnexpectedError(describe(error)).apply {
        stackTrace = error.stackTrace
        val cause = error.cause
        if (cause != null && cause !== error && depth < MAX_CAUSES) initCause(chain(cause, depth + 1))
    }

    /** The type, plus Firebase's error code where there is one (a fixed name, never user data). */
    private fun describe(error: Throwable): String = when (error) {
        is FirebaseFirestoreException -> "${error.javaClass.name} ${error.code.name}"
        is FirebaseAuthException -> "${error.javaClass.name} ${error.errorCode}"
        else -> error.javaClass.name
    }

    private const val MAX_CAUSES = 4

    /** "io.github.liquidlyf.navigation.RequestDetail/{id}" → "RequestDetail": no package, no argument pattern. */
    fun screenName(route: String): String = route.substringBefore('/').substringBefore('?').substringAfterLast('.')
}

/** What a non-fatal report holds: [message] is the original exception's type, not its message. */
class UnexpectedError(message: String) : Exception(message)
