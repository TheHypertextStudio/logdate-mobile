package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XrpcBlobErrorsTest {
    @Test fun identityFencesHaveStableConflictCodes() {
        val held = blobFailureResponse(IdentityWriteRefusedException(IdentityFenceOutcome.HELD))
        assertEquals(HttpStatusCode.Conflict, held.status)
        assertEquals("IdentityAdoptionInProgress", held.body.error)
        val changed = blobFailureResponse(IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED))
        assertEquals(HttpStatusCode.Conflict, changed.status)
        assertEquals("IdentityChanged", changed.body.error)
        val deleted = blobFailureResponse(IdentityWriteRefusedException(IdentityFenceOutcome.DELETED))
        assertEquals(HttpStatusCode.Unauthorized, deleted.status)
        assertEquals("AccountUnavailable", deleted.body.error)
    }

    @Test fun providerAndValidationErrorsNeverExposeTheirRawMessage() {
        for (error in listOf(IllegalStateException("private storage path"), IllegalArgumentException("private request value"))) {
            val response = blobFailureResponse(error)
            assertFalse(response.body.message.contains("private"))
        }
        assertEquals(HttpStatusCode.InternalServerError, blobFailureResponse(IllegalStateException()).status)
        assertEquals(HttpStatusCode.BadRequest, blobFailureResponse(IllegalArgumentException()).status)
    }
}
