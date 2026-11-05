@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.space.SpaceRef
import app.logdate.space.adoption.IdentityAdoptionBinding
import app.logdate.space.adoption.IdentityAdoptionManifest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class IdentityAdoptionRepositoryCoveragePostgresTest {
    @Test fun aSharedBlockCannotHideAnUnconvertedCurrentLogicalRecord() =
        fixture {
            val first = copy("first", "one")
            mappings(listOf(first))
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun everyLogicalRecordCanHaveItsOwnVerifiedTarget() =
        fixture {
            val first = copy("first", "one")
            val second = copy("second", "two")
            mappings(listOf(first, second))
            val coverage = assertNotNull(coverage())
            assertEquals(2L, coverage.targetRecords)
            assertEquals(2L, coverage.originCount)
        }

    @Test fun equivalentLogicalRecordsCanShareOneTargetWithTwoOrigins() =
        fixture {
            val first = copy("first", "shared")
            val second = copy("second", "shared")
            mappings(listOf(first, second))
            val coverage = assertNotNull(coverage())
            assertEquals(1L, coverage.targetRecords)
            assertEquals(2L, coverage.originCount)
        }

    @Test fun oneLogicalRecordCanProduceAnEntryAndTranscriptWithoutHidingAnotherRecord() =
        fixture {
            val entry = copy("first", "entry")
            val transcript = copy("first", "transcript")
            val second = copy("second", "other")
            mappings(listOf(entry, transcript, second))
            val coverage = assertNotNull(coverage())
            assertEquals(3L, coverage.targetRecords)
            assertEquals(3L, coverage.originCount)
        }

    @Test fun repositoryFanoutCanBeInventoriedAndPromotedWithEveryOrigin() =
        fixture {
            val copies = listOf(copy("first", "entry"), copy("first", "transcript"), copy("second", "other"))
            mappings(copies)
            assertNotNull(coverage())
            auth.source.blueskyTransaction {
                val inventory = IdentityAdoptionManifestInventory(this, held, copies.first().record.generation)
                assertEquals(3L, inventory.records().count)
                IdentityAdoptionPromotion(this, held, copies.first().record.generation).promote()
                for (table in listOf("identity_adoption_record_receipts", "identity_adoption_origin_receipts")) {
                    createStatement().use {
                        it.executeQuery("SELECT count(*) FROM $table").use { rows ->
                            check(rows.next())
                            assertEquals(3L, rows.getLong(1))
                        }
                    }
                }
            }
        }

    @Test fun repositoryFanoutSurvivesSignedVerificationAndAtomicIdentityCutover() =
        fixture {
            val copies = listOf(copy("first", "entry"), copy("first", "transcript"), copy("second", "other"))
            mappings(copies)
            val proof = assertNotNull(coverage())
            val manifest =
                auth.source.blueskyTransaction {
                    val inventory = IdentityAdoptionManifestInventory(this, held, copies.first().record.generation)
                    val binding = held.binding
                    IdentityAdoptionManifest(
                        IdentityAdoptionBinding(
                            Uuid.parse(held.id),
                            binding.accountId,
                            auth.device.origin,
                            binding.deviceId,
                            SpaceRef.personal(binding.sourceDid),
                            SpaceRef.personal(binding.targetDid),
                            binding.sourceEpoch,
                            inventory.sourceSequence(),
                        ),
                        inventory.records(),
                        inventory.blobs(),
                        Bytes(ByteArray(32) { 17 }),
                        Bytes(ByteArray(32) { 19 }),
                    )
                }
            val verify = IdentityAdoptionVerificationRequest(manifest.canonicalBytes(), proof.digest)
            val verifyTicket =
                assertNotNull(
                    auth.actions.issue(
                        held,
                        IdentityAdoptionAction.VERIFY,
                        verificationDigest(verify),
                        auth.clock.now(),
                    ),
                )
            val verified =
                assertNotNull(
                    PostgreSQLIdentityAdoptionVerification(auth.source, auth.clock).verify(
                        held,
                        verifyTicket,
                        auth.actionSignature(verifyTicket, held),
                        verify,
                        Long.MAX_VALUE,
                    ),
                )
            val commit = IdentityAdoptionCommitRequest(verified.manifestDigest, verified.coverageDigest)
            val commitTicket =
                assertNotNull(
                    auth.actions.issue(
                        verified.operation,
                        IdentityAdoptionAction.COMMIT,
                        adoptionCommitDigest(commit),
                        auth.clock.now(),
                    ),
                )
            val receipt =
                assertNotNull(
                    PostgreSQLIdentityAdoptionCommit(auth.source, auth.clock).commit(
                        verified.operation,
                        commitTicket,
                        auth.actionSignature(commitTicket, verified.operation),
                        commit,
                        Long.MAX_VALUE,
                    ),
                )
            assertEquals(held.binding.targetDid, receipt.targetDid)
            assertEquals(held.binding.sourceEpoch + 1, receipt.identityEpoch)
            for (copy in copies) {
                val actual =
                    assertNotNull(
                        PostgreSQLSpaceStore().get(
                            SpaceRef.personal(receipt.targetDid).uri,
                            receipt.targetDid,
                            copy.record.rkey,
                        ),
                    )
                assertEquals(copy.record.record, actual.record)
                assertEquals(copy.origin.targetSequence, actual.seq)
            }
        }

    @Test fun previouslyMigratedKnownRepositoryRecordsRemainHistoryWithoutBeingResurrected() =
        fixture(retiredLegacy = true) {
            mappings(emptyList())
            val value = assertNotNull(coverage())
            assertEquals(0L, value.targetRecords)
            assertEquals(0L, value.originCount)
        }

    @Test fun anUnknownCurrentSelectorSharingRetiredBytesStillRequiresItsOwnConvertedOrigin() =
        fixture(retiredLegacy = true, mixed = true) {
            val current = copy("second", "current")
            mappings(listOf(current))
            val value = assertNotNull(coverage())
            assertEquals(1L, value.targetRecords)
            assertEquals(1L, value.originCount)
        }

    private fun fixture(
        retiredLegacy: Boolean = false,
        mixed: Boolean = false,
        block: suspend Setup.() -> Unit,
    ) = withIdentityAdoptionAuthorization {
        val did = AtprotoDid.require(operation.binding.sourceDid)
        val firstCollection = if (retiredLegacy) "studio.hypertext.logdate.content" else "future.example.record"
        val secondCollection = if (retiredLegacy && !mixed) firstCollection else "future.example.record"
        val memory = InMemoryRepoBlockStore()
        val engine = DefaultRepoEngine(memory)
        val body = buildJsonObject { put("unknown", "preserved") }
        val shared =
            engine
                .putRecord(
                    RepoRecordId(did, Nsid.require(firstCollection), RecordKey.require("first")),
                    body,
                ).getOrThrow()
                .cid
        val second =
            engine
                .putRecord(
                    RepoRecordId(did, Nsid.require(secondCollection), RecordKey.require("second")),
                    body,
                ).getOrThrow()
                .cid
        assertEquals(shared, second)
        val head = checkNotNull(memory.readHead(did).getOrThrow())
        val commits = memory.listCommits(did).getOrThrow()
        val blocks = memory.listBlocks(did).getOrThrow()
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO atproto_repo_heads(repo_did,root_cid," +
                    "commit_cid,revision,updated_at) VALUES (?,?,?,?,?)",
            ).use {
                it.setString(1, did.toString())
                it.setString(2, head.root.value)
                it.setString(3, head.commitCid.value)
                it.setLong(4, head.revision)
                it.setTimestamp(5, clock.now().timestamp())
                it.executeUpdate()
            }
            for (row in blocks) {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES (?,?)").use {
                    it.setString(1, row.cid.value)
                    it.setBytes(2, row.bytes)
                    it.executeUpdate()
                }
                prepareStatement("INSERT INTO atproto_repo_block_links(repo_did,cid) VALUES (?,?)").use {
                    it.setString(1, did.toString())
                    it.setString(2, row.cid.value)
                    it.executeUpdate()
                }
            }
            for (row in commits) {
                prepareStatement(
                    "INSERT INTO atproto_repo_commits(repo_did,revision,cid,root_cid,prev_cid," +
                        "created_at_epoch_millis,record_count,signature) VALUES (?,?,?,?,?,?,?,?)",
                ).use {
                    it.setString(1, did.toString())
                    it.setLong(2, row.commit.revision)
                    it.setString(3, row.cid.value)
                    it.setString(4, row.commit.root.value)
                    it.setString(5, row.commit.prev?.value)
                    it.setLong(6, row.commit.createdAtEpochMillis)
                    it.setInt(7, row.commit.recordCount)
                    it.setString(8, row.signature)
                    it.executeUpdate()
                }
            }
        }
        if (retiredLegacy) seedCompletedStorageMove()
        authorize()
        val held =
            (
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    clock.now(),
                ) as IdentityAdoptionOutcome.Accepted
            ).value
        Setup(this, held, shared, firstCollection, secondCollection).block()
    }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
        val held: IdentityAdoptionOperation,
        val cid: String,
        val firstCollection: String,
        val secondCollection: String,
    ) {
        suspend fun copy(
            logicalKey: String,
            target: String,
        ): IdentityAdoptionGenericCopy {
            val item =
                checkNotNull(
                    PostgreSQLIdentityAdoptionSourceInventory(auth.source)
                        .page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = auth.clock.now()),
                ).items.single { it.key == listOf(cid) }
            val request =
                IdentityAdoptionGenericRequest(
                    IdentityAdoptionSourceReference(IdentityAdoptionSourceKind.REPO_BLOCK, listOf(cid)),
                    HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(IdentityAdoptionSourceKind.REPO_BLOCK, item)),
                    target,
                    genericSealed(target),
                    emptyList(),
                    IdentityAdoptionSourceRecordKey(if (logicalKey == "first") firstCollection else secondCollection, logicalKey),
                )
            val ticket =
                assertNotNull(
                    auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request), auth.clock.now()),
                )
            val staging = PostgreSQLIdentityAdoptionGenericStaging(auth.source)
            val copy =
                assertNotNull(
                    staging.put(held, ticket, auth.actionSignature(ticket, held), request, Long.MAX_VALUE, auth.clock.now()),
                )
            val read =
                assertNotNull(
                    auth.actions.issue(held, IdentityAdoptionAction.READ_STAGED, genericReadDigest(request), auth.clock.now()),
                )
            assertEquals(copy, staging.read(held, read, auth.actionSignature(read, held), request, auth.clock.now()))
            val replay =
                assertNotNull(
                    auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request), auth.clock.now()),
                )
            assertEquals(copy, staging.put(held, replay, auth.actionSignature(replay, held), request, Long.MAX_VALUE, auth.clock.now()))
            return copy
        }

        suspend fun mappings(copies: List<IdentityAdoptionGenericCopy>) {
            val reader = PostgreSQLIdentityAdoptionSourceInventory(auth.source)
            val repository =
                assertNotNull(
                    PostgreSQLIdentityAdoptionRepositoryInventory(auth.source).read(held, auth.clock.now()),
                ).inventory
            val roles = repository.blocks.associate { it.cid to it.roles }
            for (kind in IdentityAdoptionSourceKind.entries) {
                val page = assertNotNull(reader.page(held, kind, now = auth.clock.now()))
                for (item in page.items) {
                    val disposition =
                        if (kind == IdentityAdoptionSourceKind.REPO_BLOCK && item.key == listOf(cid)) {
                            if (copies.isEmpty()) IdentityAdoptionDisposition.RETAINED_HISTORY else IdentityAdoptionDisposition.CONVERTED
                        } else if (kind == IdentityAdoptionSourceKind.REPO_BLOCK &&
                            roles.getValue(item.key.single()).any {
                                it.name in setOf("HISTORICAL_RECORD", "UNREFERENCED", "RAW")
                            }
                        ) {
                            IdentityAdoptionDisposition.RETAINED_HISTORY
                        } else {
                            IdentityAdoptionDisposition.STRUCTURAL
                        }
                    val targets =
                        if (disposition == IdentityAdoptionDisposition.CONVERTED) {
                            copies
                                .map {
                                    IdentityAdoptionTargetReference(it.record.rkey, it.record.targetDigest)
                                }.distinctBy { it.rkey }
                                .sortedBy { it.rkey }
                        } else {
                            emptyList()
                        }
                    val request =
                        IdentityAdoptionMappingRequest(
                            IdentityAdoptionSourceReference(kind, item.key),
                            HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
                            disposition,
                            targets,
                            emptyList(),
                        )
                    val ticket =
                        assertNotNull(
                            auth.actions.issue(
                                held,
                                IdentityAdoptionAction.STAGE_RECORDS,
                                prepareIdentityAdoptionMapping(request).bodyDigest,
                                auth.clock.now(),
                            ),
                        )
                    assertNotNull(
                        PostgreSQLIdentityAdoptionMappings(auth.source)
                            .put(held, ticket, auth.actionSignature(ticket, held), request, Long.MAX_VALUE, auth.clock.now()),
                    )
                }
            }
        }

        suspend fun coverage() = PostgreSQLIdentityAdoptionCoverage(auth.source).read(held, auth.clock.now())
    }
}
