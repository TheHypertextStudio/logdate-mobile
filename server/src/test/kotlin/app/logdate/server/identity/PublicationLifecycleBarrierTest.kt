package app.logdate.server.identity

import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PublicationLifecycleBarrierTest {
    @Test fun evidenceStorageCannotStandInForLifecycleAdmission() =
        runTest {
            val history =
                object : HostedPlcOperationRepository by InMemoryHostedPlcOperationRepository() {
                    override suspend fun beginPublicationAttempt(
                        accountId: Uuid,
                        did: String,
                        identityEpoch: Long,
                    ) = false

                    override suspend fun recordPublicationAttempt(did: String) = true
                }
            var submissions = 0
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
                        submissions++
                        return Result.success(Unit)
                    }
                }
            val publisher =
                PlcIdentityService(
                    SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-kek"),
                    AtprotoIdentityConfig(publishHostedPlcOperations = true),
                    directory,
                    history,
                )
            val identity = publisher.provisionHostedDid(Uuid.random(), "owner.logdate.test")
            assertEquals(0, submissions)
            assertFailsWith<IdentityLifecycleConflictException> {
                publisher.publishStoredGenesis(history.listByDid(identity.did).single())
            }
            assertEquals(0, submissions)
        }
}
