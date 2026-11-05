@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdentityAdoptionMediaMirrorsPostgresTest {
    @Test fun mirroredRowsKeepIndependentProvenanceAndOneDistinctVerifiedBlob() =
        withPrivateAdoptionMedia {
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_media_records(media_id,user_id,content_id,file_name,mime_type,size_bytes," +
                        "data,created_at,version,device_id) " +
                        "VALUES ('synthetic-media',?,'synthetic-content','synthetic','image/jpeg',3,decode('010203','hex'),0,1,'device-a')",
                ).use {
                    it.setObject(1, current.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val kind = IdentityAdoptionSourceKind.MEDIA
            val item =
                assertNotNull(
                    PostgreSQLIdentityAdoptionSourceInventory(auth.source).page(current, kind, now = auth.clock.now()),
                ).items.single()
            val mirror =
                IdentityAdoptionMediaUpload(
                    Uuid.random().toString(),
                    IdentityAdoptionSourceReference(kind, item.key),
                    HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
                    upload.cid,
                    upload.size,
                )
            assertNotNull(begin())
            assertNotNull(write())
            transition(IdentityAdoptionPhase.FENCED)
            assertNotNull(verify())
            assertNotNull(begin(mirror))

            suspend fun action(
                value: IdentityAdoptionMediaUpload,
                name: String,
            ) = assertNotNull(
                auth.actions.issue(
                    current,
                    if (name == "write") IdentityAdoptionAction.BEGIN_BLOB else IdentityAdoptionAction.READ_STAGED,
                    mediaUploadActionDigest(value.uploadId, name),
                    auth.clock.now(),
                ),
            )
            val write = action(mirror, "write")
            assertNotNull(
                ingress().write(current, write, auth.actionSignature(write, current), mirror.uploadId, mirror.size, bytes.inputStream()),
            )
            val verify = action(mirror, "verify")
            assertNotNull(ingress().verify(current, verify, auth.actionSignature(verify, current), mirror.uploadId))
            assertEquals(upload.size * 2, used())
            auth.mapStorageAuthority(current)
            for (value in listOf(upload, mirror)) {
                val mapping =
                    IdentityAdoptionMappingRequest(
                        value.source,
                        value.sourceDigest,
                        IdentityAdoptionDisposition.MEDIA,
                        emptyList(),
                        listOf(value.cid),
                    )
                val proof =
                    assertNotNull(
                        auth.actions.issue(
                            current,
                            IdentityAdoptionAction.STAGE_RECORDS,
                            prepareIdentityAdoptionMapping(mapping).bodyDigest,
                            auth.clock.now(),
                        ),
                    )
                assertNotNull(
                    PostgreSQLIdentityAdoptionMappings(auth.source).put(
                        current,
                        proof,
                        auth.actionSignature(proof, current),
                        mapping,
                        Long.MAX_VALUE,
                        auth.clock.now(),
                    ),
                )
            }
            val coverage = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
            assertEquals(1L, coverage.targetMedia)
            assertEquals(3L, coverage.mappingCount)
            val retainedMetadata = used() - upload.size * 2
            transition(IdentityAdoptionPhase.CANCELED)
            val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(auth.source, storage, auth.clock)
            assertTrue(cleanup.cleanup(upload.uploadId))
            assertTrue(cleanup.cleanup(mirror.uploadId))
            assertEquals(retainedMetadata, used())
        }
}
