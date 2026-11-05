package app.logdate.server.atproto

import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.server.logdate.RepoWriteTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.CreateRecordRequest
import studio.hypertext.atproto.pds.DeleteRecordRequest
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PutRecordRequest
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AccountBoundPdsRepoServiceTest {
    private val owner = Uuid.random()
    private val delegate = mockk<PdsRepoService>()
    private val transaction = RecordingTransaction()
    private val service = AccountBoundPdsRepoService(delegate, transaction)
    private val repo = AtprotoDid.require("did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
    private val collection = Nsid.require("app.test.record")
    private val key = RecordKey.require("entry")
    private val value = buildJsonObject { }

    @Test
    fun writesUseTheOriginalAccountAndExplicitEpoch() =
        runBlocking {
            val refusal = IllegalStateException("delegate failure")
            coEvery { delegate.createRecord(any()) } coAnswers {
                check(transaction.entered) { "Write escaped the owned transaction" }
                Result.failure(refusal)
            }
            coEvery { delegate.putRecord(any()) } coAnswers {
                check(transaction.entered) { "Write escaped the owned transaction" }
                Result.failure(refusal)
            }
            coEvery { delegate.deleteRecord(any()) } coAnswers {
                check(transaction.entered) { "Write escaped the owned transaction" }
                Result.failure(refusal)
            }
            withAccountWriteOrigin(owner, 7) {
                assertSame(refusal, service.createRecord(CreateRecordRequest(repo, collection, value)).exceptionOrNull())
                assertSame(refusal, service.putRecord(PutRecordRequest(repo, collection, key, value)).exceptionOrNull())
                assertSame(refusal, service.deleteRecord(DeleteRecordRequest(repo, collection, key)).exceptionOrNull())
            }
            assertEquals(List(3) { UUID.fromString(owner.toString()) to 7L }, transaction.origins)
            assertEquals(3, transaction.rolledBack)
        }

    @Test
    fun noOriginCannotInferAuthorityFromTheRequestedDid() =
        runBlocking {
            coEvery { delegate.deleteRecord(any()) } returns Result.success(true)
            assertIs<IdentityWriteRefusedException>(service.deleteRecord(DeleteRecordRequest(repo, collection, key)).exceptionOrNull())
            coVerify(exactly = 0) { delegate.deleteRecord(any()) }
        }

    @Test
    fun admissionFailureCannotEnterTheDelegate() =
        runBlocking {
            val refusal = IllegalStateException("held")
            transaction.refusal = refusal
            coEvery { delegate.deleteRecord(any()) } returns Result.success(true)
            withAccountWriteOrigin(owner) {
                assertSame(refusal, service.deleteRecord(DeleteRecordRequest(repo, collection, key)).exceptionOrNull())
            }
            coVerify(exactly = 0) { delegate.deleteRecord(any()) }
        }

    @Test
    fun cancellationEscapesAndRollsBack() =
        runBlocking {
            coEvery { delegate.deleteRecord(any()) } returns Result.failure(CancellationException("cancelled"))
            withAccountWriteOrigin(owner) {
                assertFailsWith<CancellationException> { service.deleteRecord(DeleteRecordRequest(repo, collection, key)) }
            }
            assertEquals(1, transaction.rolledBack)
        }

    private class RecordingTransaction : RepoWriteTransaction {
        val origins = mutableListOf<Pair<UUID, Long>>()
        var entered = false
        var refusal: Throwable? = null
        var rolledBack = 0

        override suspend fun <T> write(action: suspend () -> T): T = error("Unbound transaction")

        override suspend fun <T> write(
            userId: UUID,
            identityEpoch: Long,
            action: suspend () -> T,
        ): T {
            refusal?.let { throw it }
            origins += userId to identityEpoch
            entered = true
            try {
                return action()
            } catch (failure: Throwable) {
                rolledBack++
                throw failure
            } finally {
                entered = false
            }
        }
    }
}
