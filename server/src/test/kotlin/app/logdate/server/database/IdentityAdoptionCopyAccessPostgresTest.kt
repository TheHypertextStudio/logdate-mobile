@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionCopyAccessPostgresTest {
    @Test fun signedCopyReadbackMappingAndCoverageKeepTheSourceCanonical() =
        withGenericAdoption {
            hold()
            val access = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { Long.MAX_VALUE }, auth.clock)
            val request = request()
            val ticket = assertNotNull(access.issue(current, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request)))
            val copied = assertNotNull(access.put(current, ticket, auth.actionSignature(ticket, current), request))
            assertEquals(7L, copied.origin.sourceVersion)
            val read = assertNotNull(access.issue(current, IdentityAdoptionAction.READ_STAGED, genericReadDigest(request)))
            assertEquals(copied, access.read(current, read, auth.actionSignature(read, current), request))
            val mapping =
                IdentityAdoptionMappingRequest(
                    request.source,
                    request.sourceDigest,
                    IdentityAdoptionDisposition.CONVERTED,
                    listOf(IdentityAdoptionTargetReference(request.rkey, copied.record.targetDigest)),
                    emptyList(),
                )
            val mapDigest = prepareIdentityAdoptionMapping(mapping).bodyDigest
            val mapTicket = assertNotNull(access.issue(current, IdentityAdoptionAction.STAGE_RECORDS, mapDigest))
            val saved = assertNotNull(access.putMapping(current, mapTicket, auth.actionSignature(mapTicket, current), mapping))
            val mappingRead = assertNotNull(access.issue(current, IdentityAdoptionAction.READ_STAGED, mappingReadDigest(mapping)))
            assertEquals(saved, access.readMapping(current, mappingRead, auth.actionSignature(mappingRead, current), request.source))
            auth.mapStorageAuthority(current)
            val cover = assertNotNull(access.issue(current, IdentityAdoptionAction.READ_STAGED, copyCoverageDigest()))
            val covered = assertNotNull(access.coverage(current, cover, auth.actionSignature(cover, current)))
            assertEquals(7L, covered.sourceSequence)
            assertEquals(1L, covered.coverage.targetRecords)
            assertEquals(1L, covered.coverage.originCount)
            assertEquals(20, covered.coverage.source.sources.size)
            val targetSpace = SpaceRef.personal(current.binding.targetDid).uri
            assertNull(PostgreSQLSpaceStore().get(targetSpace, current.binding.targetDid, request.rkey))
            assertEquals(current.binding.sourceDid, PostgreSQLAccountRepository().findById(current.binding.accountId)?.did)
        }

    @Test fun copyPermissionWorksBeforeTheFinalHoldWithoutChangingCanonicalIdentity() =
        withGenericAdoption {
            val access = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { Long.MAX_VALUE }, auth.clock)
            val request = request()
            val ticket = assertNotNull(access.issue(current, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request)))
            assertNotNull(access.put(current, ticket, auth.actionSignature(ticket, current), request))
            assertEquals(current.binding.sourceDid, PostgreSQLAccountRepository().findById(current.binding.accountId)?.did)
        }

    @Test fun invalidCopySignaturesAndUnsupportedActionsCannotConsumeAuthority() =
        withGenericAdoption {
            hold()
            val access = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { Long.MAX_VALUE }, auth.clock)
            val request = request()
            assertNull(access.issue(current, IdentityAdoptionAction.COMMIT, genericRequestDigest(request)))
            val ticket = assertNotNull(access.issue(current, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request)))
            val consumed = auth.countConsumed()
            val invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
            assertNull(access.put(current, ticket, invalid, request))
            assertEquals(consumed, auth.countConsumed())
            assertNotNull(access.put(current, ticket, auth.actionSignature(ticket, current), request))
        }

    @Test fun insufficientQuotaRollsBackThePrivateCopyAndItsNonce() =
        withGenericAdoption {
            hold()
            val access = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { 0L }, auth.clock)
            val request = request()
            val ticket = assertNotNull(access.issue(current, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request)))
            val consumed = auth.countConsumed()
            val signature = auth.actionSignature(ticket, current)
            assertFailsWith<IdentityAdoptionStagingException> { access.put(current, ticket, signature, request) }
            assertEquals(consumed, auth.countConsumed())
            assertEquals(0L, used())
        }

    @Test fun expiryDuringCopyCannotLeaveAStagedRecordOrConsumedNonce() =
        withGenericAdoption {
            hold()
            val request = request()
            val stable = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { Long.MAX_VALUE }, auth.clock)
            val ticket = assertNotNull(stable.issue(current, IdentityAdoptionAction.STAGE_RECORDS, genericRequestDigest(request)))
            val start = auth.clock.now()
            val observed =
                IdentityAdoptionAfterStatementFixture(auth.source, "INSERT INTO identity_adoption_staged_records", "executeUpdate") {
                    auth.clock.time = start + 121.seconds
                }
            val consumed = auth.countConsumed()
            val access = PostgreSQLIdentityAdoptionCopyAccess(observed, { Long.MAX_VALUE }, auth.clock)
            val signature = auth.actionSignature(ticket, current)
            assertFailsWith<IdentityAdoptionStagingException> { access.put(current, ticket, signature, request) }
            assertTrue(observed.executed)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(0L, used())
        }
}

internal fun mappingReadDigest(request: IdentityAdoptionMappingRequest): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.source-mapping-read")
                put("kind", request.source.kind.name)
                put("key", JsonArray(request.source.key.map(::JsonPrimitive)))
            },
        ),
    )

internal fun copyCoverageDigest(): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.coverage-read")
            },
        ),
    )
