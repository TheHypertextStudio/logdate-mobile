@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.space.SpaceRef
import app.logdate.space.adoption.IdentityAdoptionBinding
import app.logdate.space.adoption.IdentityAdoptionBlobInventory
import app.logdate.space.adoption.IdentityAdoptionManifest
import app.logdate.space.adoption.IdentityAdoptionRecordEvidence
import app.logdate.space.adoption.IdentityAdoptionRecordInventory
import app.logdate.space.adoption.IdentityAdoptionWireKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.HexFormat
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

internal fun withAdoptionVerification(
    deleted: Boolean = false,
    block: suspend IdentityAdoptionVerificationFixture.() -> Unit,
) = withGenericAdoption(deleted = deleted) {
    hold()
    auth.mapStorageAuthority(current)
    val request = request(target = if (deleted) null else genericSealed("target"))
    val copy = assertNotNull(put(request))
    val mapping =
        IdentityAdoptionMappingRequest(
            request.source,
            request.sourceDigest,
            IdentityAdoptionDisposition.CONVERTED,
            listOf(IdentityAdoptionTargetReference(request.rkey, copy.record.targetDigest)),
            emptyList(),
        )
    val digest = prepareIdentityAdoptionMapping(mapping).bodyDigest
    val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
    val signature = auth.actionSignature(ticket, current)
    assertNotNull(
        PostgreSQLIdentityAdoptionMappings(auth.source).put(current, ticket, signature, mapping, Long.MAX_VALUE, auth.clock.now()),
    )
    val summary =
        IdentityAdoptionRecordInventory().use { inventory ->
            inventory.append(
                IdentityAdoptionRecordEvidence(
                    IdentityAdoptionWireKey(copy.record.sourceCollection, copy.record.sourceRkey),
                    IdentityAdoptionWireKey("app.logdate.crypto.sealed", copy.record.rkey),
                    copy.record.sourceVersion,
                    Bytes(HexFormat.of().parseHex(copy.record.sourceDigest)),
                    Bytes(HexFormat.of().parseHex(copy.record.targetDigest)),
                    deleted,
                ),
            )
            inventory.finish()
        }
    val binding = current.binding
    val manifest =
        IdentityAdoptionManifest(
            IdentityAdoptionBinding(
                Uuid.parse(current.id),
                binding.accountId,
                auth.device.origin,
                binding.deviceId,
                SpaceRef.personal(binding.sourceDid),
                SpaceRef.personal(binding.targetDid),
                binding.sourceEpoch,
                7,
            ),
            summary,
            IdentityAdoptionBlobInventory().use { it.finish() },
            Bytes(ByteArray(32) { 17 }),
            Bytes(ByteArray(32) { 19 }),
        )
    val coverage = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
    IdentityAdoptionVerificationFixture(this, manifest, coverage.digest).block()
}

internal class IdentityAdoptionVerificationFixture(
    val copies: IdentityAdoptionGenericFixture,
    val manifest: IdentityAdoptionManifest,
    val coverage: String,
) {
    val auth get() = copies.auth
    val operation get() = copies.current
    val verifier = PostgreSQLIdentityAdoptionVerification(auth.source, auth.clock)

    fun request(
        value: IdentityAdoptionManifest = manifest,
        coverageDigest: String = coverage,
    ) = IdentityAdoptionVerificationRequest(value.canonicalBytes(), coverageDigest)

    suspend fun verify(
        value: IdentityAdoptionVerificationRequest = request(),
        limit: Long = Long.MAX_VALUE,
    ) = ticket(value).let { challenge -> verifier.verify(operation, challenge, auth.actionSignature(challenge, operation), value, limit) }

    suspend fun ticket(value: IdentityAdoptionVerificationRequest) =
        assertNotNull(
            auth.actions.issue(
                operation,
                IdentityAdoptionAction.VERIFY,
                verificationDigest(value),
                auth.clock.now(),
            ),
        )

    suspend fun count(): Long =
        auth.source.blueskyTransaction {
            createStatement().use {
                it.executeQuery("SELECT count(*) FROM identity_adoption_verifications").use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }
}

internal fun verificationDigest(request: IdentityAdoptionVerificationRequest) =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.verify")
                put("manifestDigest", stagingHash(request.manifest))
                put("coverageDigest", request.coverageDigest)
            },
        ),
    )
