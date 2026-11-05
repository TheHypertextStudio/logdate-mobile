@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStorage
import studio.hypertext.atproto.repo.Cid
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

internal fun withPrivateAdoptionMedia(
    kind: IdentityAdoptionSourceKind = IdentityAdoptionSourceKind.SYNC_MEDIA,
    block: suspend PrivateAdoptionMediaFixture.() -> Unit,
) = withIdentityAdoptionAuthorization {
    source.blueskyTransaction {
        val sql =
            when (kind) {
                IdentityAdoptionSourceKind.SYNC_MEDIA ->
                    "INSERT INTO sync_media(media_id,user_id,file_name,mime_type,data,size_bytes," +
                        "content_id,created_at,server_version,device_id) " +
                        "VALUES ('synthetic-media',?,'synthetic','image/jpeg',decode('010203','hex'),3,'synthetic-content',0,1,'device-a')"
                IdentityAdoptionSourceKind.MEDIA ->
                    "INSERT INTO logdate_media_records(media_id,user_id,file_name,mime_type,data,size_bytes," +
                        "content_id,created_at,version,device_id) " +
                        "VALUES ('synthetic-media',?,'synthetic','image/jpeg',decode('010203','hex'),3,'synthetic-content',0,1,'device-a')"
                else ->
                    "INSERT INTO logdate_atproto_blobs(user_id,cid,mime_type,size_bytes,storage_path,created_at) " +
                        "VALUES (?,'synthetic-source-cid','image/jpeg',3,'synthetic/external/object',0)"
            }
        prepareStatement(sql).use {
            it.setObject(1, operation.binding.accountId.toJavaUUID())
            it.executeUpdate()
        }
    }
    authorize()
    val item = assertNotNull(PostgreSQLIdentityAdoptionSourceInventory(source).page(operation, kind, null, 32, clock.now())).items.single()
    val bytes = ByteArray(65537) { (it * 13).toByte() }
    val upload =
        IdentityAdoptionMediaUpload(
            Uuid.random().toString(),
            IdentityAdoptionSourceReference(kind, item.key),
            java.util.HexFormat
                .of()
                .formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
            Cid.rawSha256(bytes).value,
            bytes.size.toLong(),
        )
    PrivateAdoptionMediaFixture(this, bytes, upload).block()
}

internal class PrivateAdoptionMediaFixture(
    val auth: IdentityAdoptionAuthorizedFixture,
    val bytes: ByteArray,
    val upload: IdentityAdoptionMediaUpload,
) {
    var current = auth.operation
    val storage = InMemoryLogDateBlobStorage()
    var limit = 10000000L

    fun ingress(provider: LogDateBlobStorage = storage) =
        PostgreSQLIdentityAdoptionPrivateMedia(auth.source, provider, { limit }, auth.clock)

    suspend fun begin(value: IdentityAdoptionMediaUpload = upload) =
        auth.actions.issue(current, IdentityAdoptionAction.BEGIN_BLOB, mediaUploadDigest(value), auth.clock.now()).let {
            val ticket = assertNotNull(it)
            ingress().begin(current, ticket, auth.actionSignature(ticket, current), value)
        }

    suspend fun ticket(action: String): IdentityAdoptionActionChallenge =
        assertNotNull(
            auth.actions.issue(
                current,
                if (action in setOf("write", "discard")) IdentityAdoptionAction.BEGIN_BLOB else IdentityAdoptionAction.READ_STAGED,
                mediaUploadActionDigest(upload.uploadId, action),
                auth.clock.now(),
            ),
        )

    suspend fun write(
        body: ByteArray = bytes,
        provider: LogDateBlobStorage = storage,
    ) = ticket("write").let {
        ingress(provider).write(current, it, auth.actionSignature(it, current), upload.uploadId, upload.size, body.inputStream())
    }

    suspend fun verify(provider: LogDateBlobStorage = storage) =
        ticket("verify").let {
            ingress(provider).verify(current, it, auth.actionSignature(it, current), upload.uploadId)
        }

    suspend fun used() = PostgreSQLSpaceStore().storedBytes(current.binding.accountId.toJavaUUID())

    suspend fun row() = auth.source.blueskyTransaction { mediaUpload(current, upload.uploadId) }

    suspend fun transition(phase: IdentityAdoptionPhase) {
        current =
            (
                auth.operations.transition(current.binding.accountId, current.id, current.revision, phase, auth.clock.now())
                    as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>
            ).value
    }
}
