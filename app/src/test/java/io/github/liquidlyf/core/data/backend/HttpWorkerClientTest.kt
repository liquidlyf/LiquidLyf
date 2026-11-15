package io.github.liquidlyf.core.data.backend

import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The retry table in backend/worker/README.md, against a local HTTP server. */
class HttpWorkerClientTest {

    private val server = MockWebServer()
    private val refreshes = mutableListOf<Boolean>()
    private val waits = mutableListOf<Int>()
    private lateinit var client: HttpWorkerClient

    private val tokens = object : WorkerTokens {
        override suspend fun idToken(forceRefresh: Boolean): String {
            refreshes += forceRefresh
            return if (forceRefresh) "fresh-token" else "old-token"
        }

        override suspend fun appCheckToken(): String = "app-check"
    }

    @Before
    fun setUp() {
        server.start()
        client = HttpWorkerClient(OkHttpClient(), { server.url("/").toString() }, tokens)
        client.backoff = { waits += it }
    }

    @After
    fun tearDown() = server.close()

    private fun reply(code: Int, body: String = """{"error":"x"}""") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `202 is done and sends both tokens to the dispatch path`() = runTest {
        reply(202, """{"accepted":true,"tier":1,"messages":1}""")
        assertEquals(AppResult.Success(Dispatch(tier = 1, alreadyDispatched = false)), client.dispatch("req1"))
        val call = server.takeRequest()
        assertEquals("POST", call.method)
        assertEquals("/v1/requests/req1/dispatch", call.url.encodedPath)
        assertEquals("Bearer old-token", call.headers["Authorization"])
        assertEquals("app-check", call.headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `an earlier dispatch is still a success`() = runTest {
        reply(202, """{"accepted":true,"alreadyDispatched":true}""")
        val result = client.dispatch("req1") as AppResult.Success
        assertEquals(true, result.data.alreadyDispatched)
        assertNull(result.data.tier)
    }

    @Test
    fun `401 refreshes the ID token and retries once`() = runTest {
        reply(401)
        reply(202, """{"accepted":true,"tier":3}""")
        assertEquals(AppResult.Success(Dispatch(3, false)), client.dispatch("req1"))
        assertEquals(listOf(false, true), refreshes)
        server.takeRequest()
        assertEquals("Bearer fresh-token", server.takeRequest().headers["Authorization"])
        assertEquals(emptyList<Int>(), waits)
    }

    @Test
    fun `a second 401 gives up`() = runTest {
        reply(401)
        reply(401)
        assertEquals(AppResult.Failure(AppError.PermissionDenied), client.dispatch("req1"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `403, 404, 409 and 422 are never retried`() = runTest {
        mapOf(403 to AppError.PermissionDenied, 404 to AppError.NotFound, 409 to AppError.InvalidInput, 422 to AppError.InvalidInput)
            .forEach { (code, error) ->
                val before = server.requestCount
                reply(code)
                assertEquals("$code", AppResult.Failure(error), client.dispatch("req1"))
                assertEquals("$code", before + 1, server.requestCount)
            }
        assertEquals(emptyList<Int>(), waits)
    }

    @Test
    fun `503 is retried with backoff up to 3 times`() = runTest {
        repeat(4) { reply(503) }
        assertEquals(AppResult.Failure(AppError.Network), client.dispatch("req1"))
        assertEquals(4, server.requestCount)
        assertEquals(listOf(1, 2, 3), waits)
    }

    @Test
    fun `a 500 then success recovers, and 429 backs off`() = runTest {
        reply(500)
        reply(202, """{"accepted":true,"tier":1}""")
        assertEquals(AppResult.Success(Dispatch(1, false)), client.dispatch("req1"))
        repeat(4) { reply(429) }
        assertEquals(AppResult.Failure(AppError.RateLimited), client.dispatch("req2"))
    }

    @Test
    fun `the daily alert cap is never retried`() = runTest {
        reply(429, """{"error":"daily alert limit reached","dailyCap":true}""")
        assertEquals(AppResult.Failure(AppError.DailyAlertLimit), client.dispatch("req1"))
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Int>(), waits)
    }

    @Test
    fun `a 429 without dailyCap is the per-minute limit and is retried`() = runTest {
        reply(429, """{"error":"rate limited","dailyCap":false}""")
        reply(429, "not json")
        reply(202, """{"accepted":true,"tier":1}""")
        assertEquals(AppResult.Success(Dispatch(1, false)), client.dispatch("req1"))
        assertEquals(listOf(1, 2), waits)
    }

    @Test
    fun `notify calls hit the personal-push paths with the same retry rules`() = runTest {
        reply(202, """{"accepted":true,"type":"NEW_OFFER"}""")
        assertEquals(AppResult.Success(Unit), client.notifyOffer("req1", "donor1"))
        assertEquals("/v1/requests/req1/responses/donor1/notify", server.takeRequest().url.encodedPath)

        reply(503)
        reply(202, """{"accepted":true,"type":"REQUEST_FULFILLED"}""")
        assertEquals(AppResult.Success(Unit), client.notifyResponders("req1"))
        assertEquals("/v1/requests/req1/notify-responders", server.takeRequest().url.encodedPath)
        assertEquals(listOf(1), waits)

        reply(409, """{"error":"nothing to announce"}""")
        assertEquals(AppResult.Failure(AppError.InvalidInput), client.notifyOffer("req1", "donor1"))
    }

    @Test
    fun `an ask to donate hits its own path, and the daily ask cap is never retried`() = runTest {
        reply(202, """{"accepted":true,"type":"ASKED_TO_DONATE"}""")
        assertEquals(AppResult.Success(Unit), client.notifyAsk("req1", "donor1"))
        assertEquals("/v1/requests/req1/asks/donor1/notify", server.takeRequest().url.encodedPath)

        reply(429, """{"error":"daily ask limit reached","dailyAskCap":true}""")
        assertEquals(AppResult.Failure(AppError.DailyAskLimit), client.notifyAsk("req1", "donor1"))
        assertEquals(2, server.requestCount)
        assertEquals(emptyList<Int>(), waits)

        reply(409, """{"error":"no such ask"}""")
        assertEquals(AppResult.Failure(AppError.InvalidInput), client.notifyAsk("req1", "donor1"))
    }

    @Test
    fun `no Worker configured yet is a quiet failure, with no call`() = runTest {
        val unconfigured = HttpWorkerClient(OkHttpClient(), { null }, tokens)
        assertEquals(AppResult.Failure(AppError.Unknown), unconfigured.dispatch("req1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signed out is treated like a refused token`() = runTest {
        val signedOut = object : WorkerTokens {
            override suspend fun idToken(forceRefresh: Boolean): String? = null
            override suspend fun appCheckToken(): String? = null
        }
        val c = HttpWorkerClient(OkHttpClient(), { server.url("/").toString() }, signedOut)
        assertEquals(AppResult.Failure(AppError.PermissionDenied), c.dispatch("req1"))
        assertEquals(0, server.requestCount)
    }
}
