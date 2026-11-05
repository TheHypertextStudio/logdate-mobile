package app.logdate.server.identity

import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PublicationAttemptHistoryTest {
    @Test
    fun privateGenesisRecordsExplicitLocalOnlyHistory() =
        runTest {
            val history = InMemoryHostedPlcOperationRepository()
            val service =
                PlcIdentityService(
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    AtprotoIdentityConfig(),
                    hostedPlcOperationRepository = history,
                )
            val identity = service.provisionHostedDid(Uuid.random(), "alice.logdate.app")
            assertEquals(false, history.listByDid(identity.did).single().publicationAttempted)
            assertEquals(false, history.hasPublicationAttempt(identity.did))
        }

    @Test
    fun failedSubmissionCannotLeaveApparentlyPrivateHistory() =
        runTest {
            val history = InMemoryHostedPlcOperationRepository()
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val identity =
                PlcIdentityService(keys, AtprotoIdentityConfig(), hostedPlcOperationRepository = history)
                    .provisionHostedDid(Uuid.random(), "alice.logdate.app")
            val operation = history.listByDid(identity.did).single()
            val failure = IllegalStateException("Test provider refused the operation")
            var observedBeforeSubmission: Boolean? = null
            val directory =
                object : PlcDirectoryClient {
                    override suspend fun getDocument(did: AtprotoDid) = error("unused")

                    override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                    override suspend fun getAuditLog(did: AtprotoDid) = error("unused")

                    override suspend fun export(
                        after: String?,
                        count: Int?,
                    ) = error("unused")

                    override suspend fun submit(
                        did: AtprotoDid,
                        entry: PlcLogEntry,
                    ): Result<Unit> {
                        observedBeforeSubmission = history.listByDid(identity.did).single().publicationAttempted
                        return Result.failure(failure)
                    }
                }
            val publisher =
                PlcIdentityService(
                    keys,
                    AtprotoIdentityConfig(publishHostedPlcOperations = true),
                    directory,
                    history,
                )
            assertSame(failure, assertFailsWith<IllegalStateException> { publisher.publishStoredGenesis(operation) })
            assertEquals(true, observedBeforeSubmission)
            assertEquals(true, history.listByDid(identity.did).single().publicationAttempted)
            assertEquals(true, history.hasPublicationAttempt(identity.did))
        }

    @Test
    fun memoryHistoryCannotForgetAnAttempt() =
        runTest {
            val history = InMemoryHostedPlcOperationRepository()
            val identity =
                PlcIdentityService(
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    AtprotoIdentityConfig(),
                    hostedPlcOperationRepository = history,
                ).provisionHostedDid(Uuid.random(), "alice.logdate.app")
            val operation = history.listByDid(identity.did).single()
            assertTrue(history.markPublicationAttempted(operation.id))
            assertFailsWith<IllegalArgumentException> { history.save(operation.copy(publicationAttempted = false)) }
            assertEquals(true, history.listByDid(identity.did).single().publicationAttempted)
        }

    @Test
    fun aNewGenesisCandidateIsDurableBeforeSubmission() =
        runTest {
            val history = InMemoryHostedPlcOperationRepository()
            val failure = IllegalStateException("Test provider refused the operation")
            var attemptedDid: String? = null
            var observedBeforeSubmission: Boolean? = null
            var observedRowWasDurable = false
            val directory =
                object : PlcDirectoryClient {
                    override suspend fun getDocument(did: AtprotoDid) = error("unused")

                    override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                    override suspend fun getAuditLog(did: AtprotoDid) = error("unused")

                    override suspend fun export(
                        after: String?,
                        count: Int?,
                    ) = error("unused")

                    override suspend fun submit(
                        did: AtprotoDid,
                        entry: PlcLogEntry,
                    ): Result<Unit> {
                        attemptedDid = did.toString()
                        observedBeforeSubmission = history.hasPublicationAttempt(did.toString())
                        observedRowWasDurable = history.listByDid(did.toString()).singleOrNull()?.publicationAttempted == true
                        return Result.failure(failure)
                    }
                }
            val publisher =
                PlcIdentityService(
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    AtprotoIdentityConfig(publishHostedPlcOperations = true),
                    directory,
                    history,
                )
            val identity = publisher.provisionHostedDid(Uuid.random(), "alice.logdate.app")
            assertEquals(false, history.listByDid(identity.did).single().publicationAttempted)
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    publisher.publishStoredGenesis(history.listByDid(identity.did).single())
                },
            )
            assertEquals(true, observedBeforeSubmission)
            val did = kotlin.test.assertNotNull(attemptedDid)
            assertTrue(observedRowWasDurable)
            assertEquals(true, history.listByDid(did).single().publicationAttempted)
            assertEquals(true, history.hasPublicationAttempt(did))
        }

    @Test
    fun publicationWithoutAHistoryStoreFailsBeforeContactingTheDirectory() =
        runTest {
            val directory =
                object : PlcDirectoryClient {
                    override suspend fun getDocument(did: AtprotoDid) = error("unused")

                    override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                    override suspend fun getAuditLog(did: AtprotoDid) = error("unused")

                    override suspend fun export(
                        after: String?,
                        count: Int?,
                    ) = error("unused")

                    override suspend fun submit(
                        did: AtprotoDid,
                        entry: PlcLogEntry,
                    ): Result<Unit> = throw AssertionError("The directory must not be contacted without durable attempt history")
                }
            val publisher =
                PlcIdentityService(
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    AtprotoIdentityConfig(publishHostedPlcOperations = true),
                    directory,
                )
            val accountId = Uuid.random()
            val identity = publisher.provisionHostedDid(accountId, "alice.logdate.app")
            val candidate =
                StoredHostedPlcOperation(
                    Uuid.random(),
                    accountId,
                    identity.did,
                    operationType = identity.operation.type,
                    operationJson =
                        kotlinx.serialization.json.Json.encodeToString(
                            studio.hypertext.atproto.plc.PlcOperation
                                .serializer(),
                            identity.operation,
                        ),
                    publicationAttempted = false,
                )
            assertFailsWith<IdentityLifecycleConflictException> {
                publisher.publishStoredGenesis(candidate)
            }
        }
}
