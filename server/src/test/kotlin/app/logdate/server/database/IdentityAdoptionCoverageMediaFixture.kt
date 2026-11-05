@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.space.SpaceBlob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

internal class IdentityAdoptionCoverageMediaFixture(
    auth: IdentityAdoptionAuthorizedFixture,
) {
    private val storage = InMemoryLogDateBlobStorage()
    private val ciphertext = ByteArray(65_537) { (it * 13).toByte() }
    val blob =
        SpaceBlob(
            Cid.rawSha256(ciphertext).value,
            "application/octet-stream",
            ciphertext.size.toLong(),
            storage.putBlob(
                LogDateBlobWriteRequest(
                    auth.operation.binding.accountId
                        .toJavaUUID(),
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = ciphertext,
                ),
            ),
        )

    suspend fun verify(
        auth: IdentityAdoptionAuthorizedFixture,
        operation: IdentityAdoptionOperation,
    ) {
        val body =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("cid", blob.cid)
                    },
                ),
            )
        val ticket = assertNotNull(auth.actions.issue(operation, IdentityAdoptionAction.BEGIN_BLOB, body, auth.clock.now()))
        val store = PostgreSQLIdentityAdoptionBlobs(auth.source)
        val signature = auth.actionSignature(ticket, operation)
        val admission = assertNotNull(store.admit(operation, ticket, signature, blob.cid, auth.clock.now()))
        val verified = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
        assertTrue(store.complete(operation, verified, auth.clock.now()))
    }
}
