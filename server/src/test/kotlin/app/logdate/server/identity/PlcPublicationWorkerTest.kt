package app.logdate.server.identity

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcDirectoryException
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcOperation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PlcPublicationWorkerTest {
    private val did = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
    private val review = mockk<PlcPublicationReview>()
    private val directory = mockk<PlcDirectoryClient>()
    private val operation = mockk<PlcOperation>()

    init {
        every { review.did } returns did
    }

    @Test
    fun `a failed audit read never authorizes a send`() =
        runTest {
            coEvery { directory.getAuditLog(did) } returns Result.failure(IOException("no response"))

            val outcome =
                PlcPublicationWorker(directory).advance(review, markAttempt = { fail("unexpected send") }, confirm = { fail() })

            assertEquals(PlcPublicationOutcome.Pending, outcome)
            coVerify(exactly = 0) { directory.submit(did, any()) }
        }

    @Test
    fun `a lost submit response leaves the review pending and the next pass reads before acting`() =
        runTest {
            val audit = listOf(mockk<PlcIndexedOperation>())
            coEvery { directory.getAuditLog(did) } returnsMany
                listOf(Result.failure(PlcDirectoryException("missing", statusCode = 404)), Result.success(audit))
            every { review.reconcile(emptyList()) } returns PlcPublicationProgress.Next(operation)
            every { review.reconcile(audit) } returns PlcPublicationProgress.Confirmed
            val document = mockk<DidDocument>()
            coEvery { directory.getDocument(did) } returns Result.success(document)
            every { review.matchesDocument(document) } returns true
            var marked = false
            coEvery { directory.submit(did, operation) } answers {
                check(marked)
                Result.failure(IOException("response lost"))
            }
            val worker = PlcPublicationWorker(directory)

            assertEquals(
                PlcPublicationOutcome.Pending,
                worker.advance(
                    review,
                    markAttempt = {
                        marked = true
                        true
                    },
                    confirm = { fail() },
                ),
            )
            assertEquals(
                PlcPublicationOutcome.Confirmed,
                worker.advance(review, markAttempt = { fail("unexpected retry") }, confirm = { true }),
            )
            coVerify(exactly = 1) { directory.submit(did, operation) }
        }

    @Test
    fun `matching audit history alone does not mark the account public`() =
        runTest {
            val audit = listOf(mockk<PlcIndexedOperation>())
            val document = mockk<DidDocument>()
            coEvery { directory.getAuditLog(did) } returns Result.success(audit)
            coEvery { directory.getDocument(did) } returns Result.success(document)
            every { review.reconcile(audit) } returns PlcPublicationProgress.Confirmed
            every { review.matchesDocument(document) } returns false

            val outcome = PlcPublicationWorker(directory).advance(review, markAttempt = { fail() }, confirm = { fail() })

            assertEquals(PlcPublicationOutcome.Conflict, outcome)
            coVerify(exactly = 0) { directory.submit(did, any()) }
        }

    @Test
    fun `a revoked claim cannot send even after the directory answers`() =
        runTest {
            coEvery { directory.getAuditLog(did) } returns Result.failure(PlcDirectoryException("missing", statusCode = 404))
            every { review.reconcile(emptyList()) } returns PlcPublicationProgress.Next(operation)

            val outcome = PlcPublicationWorker(directory).advance(review, markAttempt = { false }, confirm = { fail() })

            assertEquals(PlcPublicationOutcome.Inactive, outcome)
            coVerify(exactly = 0) { directory.submit(did, any()) }
        }
}
