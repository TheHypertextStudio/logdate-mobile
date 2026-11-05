@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedCopy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.Base64
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

internal fun withAdoptionCommit(
    deleted: Boolean = false,
    block: suspend IdentityAdoptionCommitFixture.() -> Unit,
) = withAdoptionVerification(deleted = deleted) {
    val verified = assertNotNull(verify())
    IdentityAdoptionCommitFixture(this, verified).block()
}

internal class IdentityAdoptionCommitFixture(
    val fixture: IdentityAdoptionVerificationFixture,
    val verified: IdentityAdoptionVerifiedCopy,
) {
    val auth get() = fixture.auth
    val operation get() = verified.operation
    val request = IdentityAdoptionCommitRequest(verified.manifestDigest, verified.coverageDigest)
    val service = PostgreSQLIdentityAdoptionCommit(auth.source, auth.clock)

    suspend fun ticket(value: IdentityAdoptionCommitRequest = request) =
        assertNotNull(
            auth.actions.issue(
                operation,
                IdentityAdoptionAction.COMMIT,
                adoptionCommitDigest(value),
                auth.clock.now(),
            ),
        )

    suspend fun commit(
        value: IdentityAdoptionCommitRequest = request,
        limit: Long = Long.MAX_VALUE,
    ) = ticket(value).let {
        service.commit(operation, it, auth.actionSignature(it, operation), value, limit)
    }

    suspend fun accountState(): List<String?> =
        auth.source.blueskyTransaction {
            prepareStatement(
                "SELECT did,identity_kind,storage_mode,identity_epoch,handle,signing_key_public,plc_recovery_did_key," +
                    "public_identity_did FROM accounts WHERE id = ?",
            ).use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.executeQuery().use { rows ->
                    check(rows.next())
                    (1..8).map(rows::getString)
                }
            }
        }

    suspend fun count(table: String): Long =
        auth.source.blueskyTransaction {
            check(
                table in
                    setOf(
                        "identity_adoption_receipts",
                        "retired_identity_dids",
                        "identity_adoption_record_receipts",
                        "identity_adoption_origin_receipts",
                        "identity_adoption_staged_records",
                        "identity_adoption_reservations",
                    ),
            )
            createStatement().use {
                it.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    suspend fun seedAccountMaterial() {
        auth.source.blueskyTransaction {
            val account = operation.binding.accountId.toJavaUUID()
            prepareStatement(
                "UPDATE accounts SET handle = 'private.source.example',signing_key_public = 'synthetic-public'," +
                    "plc_recovery_did_key = 'did:key:synthetic' WHERE id = ?",
            ).use {
                it.setObject(1, account)
                it.executeUpdate()
            }
            prepareStatement(
                "INSERT INTO account_key_envelopes(credential_id,account_id,ciphertext) " +
                    "SELECT credential_id,account_id,? FROM passkeys WHERE account_id = ? LIMIT 1",
            ).use {
                it.setString(1, "a".repeat(132))
                it.setObject(2, account)
                it.executeUpdate()
            }
            prepareStatement(
                "INSERT INTO account_devices(account_id,device_id,name,platform,app_version,created_at,last_active) " +
                    "VALUES (?,?,'Synthetic device','ios','test',1000,1000)",
            ).use {
                it.setObject(1, account)
                it.setObject(2, Uuid.random().toJavaUUID())
                it.executeUpdate()
            }
            for (purpose in listOf("atproto", "device-transport")) {
                prepareStatement(
                    "INSERT INTO signing_keys(id,account_id,purpose,algorithm," +
                        "public_key_multibase,private_key_encrypted,created_at) " +
                        "VALUES (?,?,?,'K-256','synthetic-public','synthetic-encrypted',?)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, account)
                    it.setString(3, purpose)
                    it.setTimestamp(4, auth.clock.now().timestamp())
                    it.executeUpdate()
                }
            }
        }
    }

    fun invalidSignature(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { 1 })
}

internal fun adoptionCommitDigest(request: IdentityAdoptionCommitRequest): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.commit")
                put("manifestDigest", request.manifestDigest)
                put("coverageDigest", request.coverageDigest)
            },
        ),
    )
