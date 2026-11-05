@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.atproto.LogDatePdsBlobStore
import app.logdate.server.auth.Account
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateAtprotoBlob
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.UploadBlobRequest
import studio.hypertext.atproto.repo.Cid
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class PdsBlobUploadAdmissionPostgresTest {
    @Test fun admittedProviderWriteHasDurableIntentAndBlocksAdoptionOutsideSql() =
        withFixture {
            val operation = begin()
            onPut = {
                assertNoIdleTransaction()
                assertEquals(listOf("WRITING"), phases())
                assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
            }
            upload().getOrThrow()
            assertEquals(listOf("ATTACHED"), phases())
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
        }

    @Test fun existingFenceRefusesBeforeAnyProviderWrite() =
        withFixture {
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(begin()))
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(0, writes)
            assertEquals(emptyList(), phases())
        }

    @Test fun providerFailureRetainsExactIntentAcrossRepositoryRestart() =
        withFixture {
            val operation = begin()
            onPut = { throw IllegalStateException("Synthetic provider acknowledgement loss") }
            assertTrue(upload().isFailure)
            assertEquals(listOf("WRITING"), phases())
            val restarted = PostgreSQLIdentityAdoptionRepository(source)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                restarted.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now()),
            )
        }

    @Test fun lateProviderCompletionAfterAccountDeletionCannotCreateMetadataOrLoseItsIntent() =
        withFixture {
            onPut = { assertTrue(runBlocking { accounts.deleteAccount(owner.id) }) }
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(listOf("RETIRED"), phases())
            assertEquals(null, blobs.getAtprotoBlob(owner.id.toJavaUUID(), cid))
            assertNotNull(lastPath)
            assertEquals(null, memory.getBlob(requireNotNull(lastPath)))
        }

    @Test fun metadataKeepsTheOriginalEpochInsteadOfBorrowingANewerEpochAfterIo() =
        withFixture {
            onPut = {
                runBlocking {
                    source.blueskyTransaction {
                        prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                            it.setObject(1, owner.id.toJavaUUID())
                            assertEquals(1, it.executeUpdate())
                        }
                    }
                }
            }
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(listOf("RETIRED"), phases())
            assertEquals(null, blobs.getAtprotoBlob(owner.id.toJavaUUID(), cid))
        }

    @Test fun authenticatedNonzeroEpochIsCarriedIntoMetadataRegistration() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            upload(epoch = 1).getOrThrow()
            assertEquals(listOf("ATTACHED"), phases())
            assertNotNull(blobs.getAtprotoBlob(owner.id.toJavaUUID(), cid))
        }

    @Test fun missingOrDifferentAuthenticatedAccountCannotBorrowTheRepoOwner() =
        withFixture {
            assertIs<IdentityWriteRefusedException>(store.putBlob(request).exceptionOrNull())
            val other = Uuid.random()
            val result = withAccountWriteOrigin(other) { store.putBlob(request) }
            assertIs<IdentityWriteRefusedException>(result.exceptionOrNull())
            assertEquals(0, writes)
        }

    @Test fun cancellationPropagatesAndRetainsPendingIntent() =
        withFixture {
            onPut = { throw CancellationException("Synthetic cancellation") }
            assertFailsWith<CancellationException> { upload() }
            assertEquals(listOf("WRITING"), phases())
        }

    @Test fun failedLateObjectDeletionRetainsAClaimForAnotherWorker() =
        withFixture {
            onPut = { assertTrue(runBlocking { accounts.deleteAccount(owner.id) }) }
            onDelete = {
                assertNoIdleTransaction()
                throw IllegalStateException("Synthetic storage failure")
            }
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(listOf("DELETE_PENDING"), phases())
            assertNotNull(memory.getBlob(requireNotNull(lastPath)))
            val restarted = PostgreSQLObjectUploads()
            val work = restarted.pendingCleanup().single()
            assertEquals(owner.id, work.origin.accountId)
            assertEquals(requireNotNull(lastPath), work.path)
            assertTrue(
                restarted.cleanup(work) { path ->
                    assertNoIdleTransaction()
                    memory.deleteBlobIfExists(path)
                    true
                },
            )
            assertEquals(listOf("RETIRED"), phases())
            assertEquals(null, memory.getBlob(requireNotNull(lastPath)))
        }

    @Test fun lostMetadataAcknowledgementNeverDeletesTheCanonicalUploadedObject() =
        withFixture {
            val delegate = uploads
            uploads =
                object : ObjectUploads by delegate {
                    override fun <T> attach(
                        upload: ObjectUpload,
                        publish: () -> T,
                        ownsResult: (T) -> Boolean,
                    ): T {
                        delegate.attach(upload, publish, ownsResult)
                        throw IllegalStateException("Synthetic metadata acknowledgement loss")
                    }
                }
            assertTrue(upload().isFailure)
            assertEquals(listOf("ATTACHED"), phases())
            assertEquals(0, deletions)
            assertNotNull(blobs.getAtprotoBlob(owner.id.toJavaUUID(), cid))
            assertNotNull(memory.getBlob(requireNotNull(lastPath)))
        }

    @Test fun repeatedContentUsesANewCandidateWithoutOverwritingTheRegisteredObject() =
        withFixture {
            upload().getOrThrow()
            val canonical = requireNotNull(lastPath)
            onPut = { throw IllegalStateException("Synthetic repeated upload failure") }
            assertTrue(upload().isFailure)
            assertEquals(2, attemptedPaths.distinct().size)
            assertEquals(canonical, blobs.getAtprotoBlob(owner.id.toJavaUUID(), cid)?.storagePath)
            assertNotNull(memory.getBlob(canonical))
        }

    @Test fun lateUploadCannotRebindToAnotherAccountThatNowOwnsTheDid() =
        withFixture {
            val replacement = owner.copy(id = Uuid.random(), username = Uuid.random().toHexString())
            var replacementPath: String? = null
            onPut = {
                runBlocking {
                    assertTrue(accounts.deleteAccount(owner.id))
                    assertTrue(accounts.create(replacement))
                }
                replacementPath =
                    memory.putBlob(
                        LogDateBlobWriteRequest(
                            replacement.id.toJavaUUID(),
                            app.logdate.server.logdate.LogDateBlobNamespace.ATPROTO,
                            "synthetic-replacement",
                            null,
                            "image/jpeg",
                            byteArrayOf(9),
                        ),
                    )
            }
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(listOf("RETIRED"), phases())
            assertEquals(null, blobs.getAtprotoBlob(replacement.id.toJavaUUID(), cid))
            assertNotNull(memory.getBlob(requireNotNull(replacementPath)))
        }

    @Test fun cleanupPreservesAnExactPathRegisteredToAnotherOwner() =
        withFixture {
            val other =
                owner.copy(
                    id = Uuid.random(),
                    username = Uuid.random().toHexString(),
                    did = "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb",
                    handle = "other.example.test",
                )
            onPut = {
                runBlocking {
                    assertTrue(accounts.deleteAccount(owner.id))
                    assertTrue(accounts.create(other))
                }
                blobs.upsertAtprotoBlob(
                    other.id.toJavaUUID(),
                    LogDateAtprotoBlob(
                        cid,
                        other.id.toJavaUUID(),
                        "image/jpeg",
                        3,
                        attemptedPaths.last(),
                        1,
                    ),
                )
            }
            assertIs<IdentityWriteRefusedException>(upload().exceptionOrNull())
            assertEquals(listOf("STORED"), phases())
            assertEquals(0, deletions)
            assertNotNull(blobs.getAtprotoBlob(other.id.toJavaUUID(), cid))
            assertNotNull(memory.getBlob(requireNotNull(lastPath)))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val blobs = CompositeLogDateMediaBlobRepository(PostgreSQLLogDateMediaRepository(), PostgreSQLLogDateAtprotoBlobRepository())
        val memory = InMemoryLogDateBlobStorage()
        val attemptedPaths = mutableListOf<String>()
        var writes = 0
        var lastPath: String? = null
        var onPut: () -> Unit = {}
        var onDelete: () -> Unit = {}
        var deletions = 0
        var uploads: ObjectUploads = PostgreSQLObjectUploads()
        private val storage =
            object : LogDateBlobStorage by memory {
                override fun deleteBlobIfExists(storagePath: String) {
                    deletions++
                    onDelete()
                    memory.deleteBlobIfExists(storagePath)
                }

                override fun putBlob(request: LogDateBlobWriteRequest): String {
                    writes++
                    attemptedPaths.add(memory.plannedPath(request))
                    onPut()
                    return memory.putBlob(request).also { lastPath = it }
                }
            }
        val request = UploadBlobRequest(AtprotoDid.require(requireNotNull(owner.did)), "image/jpeg", byteArrayOf(1, 2, 3))
        val cid = Cid.rawSha256(request.bytes).toString()
        val store get() =
            LogDatePdsBlobStore(
                AtprotoIdentityService(
                    accounts,
                    SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-key"),
                    AtprotoIdentityConfig(handleDomain = "example.test", pdsServiceEndpoint = "https://example.test"),
                ),
                blobs,
                storage,
                uploads,
            )
        private val adoption = PostgreSQLIdentityAdoptionRepository(source)

        suspend fun upload(epoch: Long = 0) = withAccountWriteOrigin(owner.id, epoch) { store.putBlob(request) }

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun hold(operation: IdentityAdoptionOperation) =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
            }

        fun phases(): List<String> =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement("SELECT phase FROM object_upload_intents WHERE account_id = ? ORDER BY id").use {
                        it.setObject(1, owner.id.toJavaUUID())
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                }
            }

        fun assertNoIdleTransaction() =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'",
                    ).use {
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val account =
                Account(
                    id,
                    id.toHexString(),
                    "Synthetic upload owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    handle = "synthetic.example.test",
                    signingKeyPublic = "synthetic-public-key",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(account))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, created_at, " +
                        "publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, id.toJavaUUID())
                    it.setString(3, account.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(id))).block()
        }
}
