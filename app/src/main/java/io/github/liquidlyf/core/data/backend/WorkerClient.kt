package io.github.liquidlyf.core.data.backend

import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's side of the Cloudflare Worker (backend/worker/README.md, "The API, for the app").
 * The Worker is the only one who can send pushes; a missed call is never fatal, because its
 * 5-minute sweep picks up any request that wasn't dispatched.
 */
interface WorkerClient {
    /** Asks the Worker to alert the first tier of donors. Call right after the create batch commits. */
    suspend fun dispatch(requestId: String): AppResult<Dispatch>
}

data class Dispatch(
    /** The tier that was alerted; null when an earlier call already did it. */
    val tier: Int?,
    val alreadyDispatched: Boolean,
)

/** Where the Worker lives: Remote Config `worker_base_url`, or the local one in the demo flavor. */
fun interface WorkerEndpoint {
    /** Null or blank while it isn't configured (before the first deploy). */
    suspend fun baseUrl(): String?
}

/** The tokens the Worker checks. Null means "none": the call is then refused or unchecked. */
interface WorkerTokens {
    suspend fun idToken(forceRefresh: Boolean): String?

    /** Monitor mode until Phase 9; null in the demo flavor, which has no App Check. */
    suspend fun appCheckToken(): String?
}

@Singleton
class HttpWorkerClient @Inject constructor(
    private val http: OkHttpClient,
    private val endpoint: WorkerEndpoint,
    private val tokens: WorkerTokens,
) : WorkerClient {

    /** Waits before retry number [attempt] (1-based). Tests replace it. */
    internal var backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(BASE_DELAY_MS shl (attempt - 1)) }

    override suspend fun dispatch(requestId: String): AppResult<Dispatch> {
        val base = endpoint.baseUrl()?.trimEnd('/')
        if (base.isNullOrBlank()) return AppResult.Failure(AppError.Unknown)
        val url = "$base/v1/requests/$requestId/dispatch"

        var refreshedToken = false
        var retries = 0
        while (true) {
            val reply = try {
                post(url, refreshedToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null // offline or timed out: retry like a 503
            }
            when (val code = reply?.code) {
                202 -> return AppResult.Success(reply!!.toDispatch())
                401 -> if (!refreshedToken) {
                    refreshedToken = true
                    continue
                } else {
                    return AppResult.Failure(AppError.PermissionDenied)
                }
                403 -> return AppResult.Failure(AppError.PermissionDenied)
                404 -> return AppResult.Failure(AppError.NotFound)
                409, 422 -> return AppResult.Failure(AppError.InvalidInput)
                else -> {
                    // The daily alert cap, not the per-minute limit: a retry gets the same answer.
                    if (code == 429 && reply!!.isDailyCap()) return AppResult.Failure(AppError.DailyAlertLimit)
                    val retryable = code == null || code == 429 || code >= 500
                    if (!retryable || retries >= MAX_RETRIES) {
                        return AppResult.Failure(if (code == 429) AppError.RateLimited else AppError.Network)
                    }
                    retries++
                    backoff(retries)
                }
            }
        }
    }

    private class Reply(val code: Int, val body: String) {
        fun toDispatch(): Dispatch {
            val json = runCatching { JSON.decodeFromString<DispatchJson>(body) }.getOrNull()
            return Dispatch(tier = json?.tier, alreadyDispatched = json?.alreadyDispatched == true)
        }

        fun isDailyCap(): Boolean =
            runCatching { JSON.decodeFromString<ErrorJson>(body).dailyCap }.getOrDefault(false)
    }

    private suspend fun post(url: String, forceRefresh: Boolean): Reply? {
        val idToken = tokens.idToken(forceRefresh) ?: return Reply(401, "")
        val appCheck = tokens.appCheckToken()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $idToken")
            .apply { if (appCheck != null) header("X-Firebase-AppCheck", appCheck) }
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { Reply(it.code, it.body.string()) }
        }
    }

    @Serializable
    private class DispatchJson(val accepted: Boolean = false, val tier: Int? = null, val alreadyDispatched: Boolean = false)

    @Serializable
    private class ErrorJson(val error: String? = null, val dailyCap: Boolean = false)

    companion object {
        /** "Retry with backoff, up to 3 times; the sweep covers the rest." */
        const val MAX_RETRIES = 3
        const val BASE_DELAY_MS = 1_000L
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
