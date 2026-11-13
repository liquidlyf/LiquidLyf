package io.github.liquidlyf.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppResultTest {

    @Test
    fun `map transforms success and keeps failure`() {
        val success: AppResult<Int> = AppResult.Success(2)
        val failure: AppResult<Int> = AppResult.Failure(AppError.Network)

        assertEquals(AppResult.Success("4"), success.map { (it * 2).toString() })
        assertEquals(AppResult.Failure(AppError.Network), failure.map { (it * 2).toString() })
    }

    @Test
    fun `onSuccess and onFailure run only for their case`() {
        var seenValue: Int? = null
        var seenError: AppError? = null

        AppResult.Success(7).onSuccess { seenValue = it }.onFailure { seenError = it }
        assertEquals(7, seenValue)
        assertNull(seenError)

        seenValue = null
        AppResult.Failure(AppError.NotFound).onSuccess { seenValue = 1 }.onFailure { seenError = it }
        assertNull(seenValue)
        assertEquals(AppError.NotFound, seenError)
    }

    @Test
    fun `getOrNull returns data or null`() {
        assertEquals("x", AppResult.Success("x").getOrNull())
        assertNull(AppResult.Failure(AppError.Unknown).getOrNull())
    }

    @Test
    fun `every error has its own message`() {
        val messages = AppError.entries.map { it.messageRes }
        assertEquals(messages.size, messages.toSet().size)
    }
}
