package io.github.liquidlyf.core.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ErrorMapperTest {

    private fun firestore(code: Code) = FirebaseFirestoreException("test", code)

    @Test
    fun `network problems map to Network`() {
        assertEquals(AppError.Network, IOException("offline").toAppError())
        assertEquals(AppError.Network, FirebaseNetworkException("offline").toAppError())
        assertEquals(AppError.Network, firestore(Code.UNAVAILABLE).toAppError())
        assertEquals(AppError.Network, firestore(Code.DEADLINE_EXCEEDED).toAppError())
    }

    @Test
    fun `firestore codes map to friendly errors`() {
        assertEquals(AppError.PermissionDenied, firestore(Code.PERMISSION_DENIED).toAppError())
        assertEquals(AppError.PermissionDenied, firestore(Code.UNAUTHENTICATED).toAppError())
        assertEquals(AppError.NotFound, firestore(Code.NOT_FOUND).toAppError())
        assertEquals(AppError.InvalidInput, firestore(Code.INVALID_ARGUMENT).toAppError())
        assertEquals(AppError.RateLimited, firestore(Code.RESOURCE_EXHAUSTED).toAppError())
        assertEquals(AppError.Unknown, firestore(Code.INTERNAL).toAppError())
    }

    @Test
    fun `anything else maps to Unknown`() {
        assertEquals(AppError.Unknown, IllegalStateException("boom").toAppError())
    }

    @Test
    fun `appResultOf wraps success and failure`() = runTest {
        assertEquals(AppResult.Success(42), appResultOf { 42 })
        assertEquals(AppResult.Failure(AppError.NotFound), appResultOf { throw firestore(Code.NOT_FOUND) })
    }

    @Test
    fun `appResultOf rethrows cancellation`() = runTest {
        try {
            appResultOf { throw CancellationException("cancelled") }
            fail("Cancellation must not become an AppResult")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
