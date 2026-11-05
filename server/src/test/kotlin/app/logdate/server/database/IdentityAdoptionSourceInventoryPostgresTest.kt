@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.recordCid
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionSourceInventoryPostgresTest {
    @Test fun allSourceKindsHaveAnExplicitPageIncludingTheRequiredAuthority() =
        fixture {
            for (kind in IdentityAdoptionSourceKind.entries) {
                val page = assertNotNull(reader.page(held, kind, now = clock.now()))
                assertEquals(kind, page.kind)
                val count = if (kind == IdentityAdoptionSourceKind.STORAGE_AUTHORITY) 1L else 0L
                assertEquals(count, page.count)
                assertEquals(count.toInt(), page.items.size)
                assertNull(page.next)
                assertEquals(held.id, page.operationId)
                assertEquals(held.revision, page.revision)
                assertEquals(held.revision, page.generation)
                assertTrue(page.fenced)
            }
        }

    @Test fun rawSyncRowsPreserveNullsExtensionsTombstonesAndAccountIsolation() =
        fixture {
            val other = createTestAccount(Uuid.random().toJavaUUID(), "did:web:other.example")
            source.blueskyTransaction {
                createStatement().use { it.execute("ALTER TABLE sync_content ADD COLUMN adoption_extension JSONB") }
                for ((id, owner, deleted) in listOf(
                    Triple("a", operation.binding.accountId.toJavaUUID(), false),
                    Triple("z", operation.binding.accountId.toJavaUUID(), true),
                    Triple("foreign", other.id.toJavaUUID(), false),
                )) {
                    prepareStatement(
                        "INSERT INTO sync_content(id,user_id,type,content,created_at,last_updated,server_version,device_id,deleted," +
                            "deleted_at,adoption_extension) VALUES (?,?,'future',NULL,1,2,3,'device',?,?,?::jsonb)",
                    ).use {
                        it.setString(1, id)
                        it.setObject(2, owner)
                        it.setBoolean(3, deleted)
                        it.setObject(4, if (deleted) 9L else null)
                        it.setString(5, "{\"unknown\":\"é🌱\",\"nullable\":null}")
                        it.executeUpdate()
                    }
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SYNC_CONTENT, now = clock.now()))
            assertEquals(2L, page.count)
            assertEquals(listOf(listOf("a"), listOf("z")), page.items.map { it.key })
            assertEquals(JsonNull, page.items.first().fields["content"])
            assertEquals(JsonPrimitive(true), page.items.last().fields["deleted"])
            assertEquals(JsonPrimitive(9), page.items.last().fields["deleted_at"])
            val extension = Json.parseToJsonElement("{\"unknown\":\"é🌱\",\"nullable\":null}")
            assertEquals(extension, page.items.first().fields["adoption_extension"])
        }

    @Test fun compositeKeysUseSeekPaginationWithoutSeparatorCollisions() =
        fixture {
            source.blueskyTransaction {
                for ((journal, content) in listOf("a" to "b~c", "a~b" to "c", "é" to "x")) {
                    prepareStatement(
                        "INSERT INTO sync_associations(journal_id,content_id,user_id,created_at,server_version,device_id) " +
                            "VALUES (?,?,?,1,2,'device')",
                    ).use {
                        it.setString(1, journal)
                        it.setString(2, content)
                        it.setObject(3, operation.binding.accountId.toJavaUUID())
                        it.executeUpdate()
                    }
                }
            }
            val keys = mutableListOf<List<String>>()
            var cursor: List<String>? = null
            do {
                val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SYNC_ASSOCIATION, cursor, 1, clock.now()))
                assertEquals(3L, page.count)
                keys += page.items.map { it.key }
                cursor = page.next
            } while (cursor != null)
            assertEquals(listOf(listOf("a", "b~c"), listOf("a~b", "c"), listOf("é", "x")), keys)
        }

    @Test fun rawRepositoryIncludesUnindexedAndRetainedBlocksWithoutProvisioning() =
        fixture {
            val bytes = "unknown raw repository content".encodeToByteArray()
            source.blueskyTransaction {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES ('retained',?)").use {
                    it.setBytes(1, bytes)
                    it.executeUpdate()
                }
                prepareStatement("INSERT INTO atproto_repo_block_links(repo_did,cid) VALUES (?,'retained')").use {
                    it.setString(1, operation.binding.sourceDid)
                    it.executeUpdate()
                }
                createStatement().use { it.execute("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES ('unowned',decode('00','hex'))") }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = clock.now()))
            assertEquals(1L, page.count)
            assertEquals(listOf("retained"), page.items.single().key)
            val binary = assertNotNull(page.items.single().binary)
            assertContentEquals(bytes, binary.bytes)
            assertEquals(hash(bytes), binary.digest)
            assertEquals(bytes.size.toLong(), binary.size)
            val states = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.COLLECTION_STATE, now = clock.now()))
            assertEquals(0L, states.count)
        }

    @Test fun inlineMediaIsHashedWithoutReturningOrJsonEncodingItsCiphertext() =
        fixture {
            val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_media_records(user_id,media_id,content_id,file_name,mime_type,size_bytes,data," +
                        "created_at,version,device_id) VALUES (?,'unreferenced','missing','photo','image/jpeg',?,?,1,2,'device')",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.setLong(2, bytes.size.toLong())
                    it.setBytes(3, bytes)
                    it.executeUpdate()
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.MEDIA, now = clock.now()))
            assertEquals(1L, page.count)
            val item = page.items.single()
            assertFalse("data" in item.fields)
            assertEquals(
                "unreferenced",
                item.fields
                    .getValue("media_id")
                    .jsonPrimitive.content,
            )
            val binary = assertNotNull(item.binary)
            assertEquals(bytes.size.toLong(), binary.size)
            assertEquals(hash(bytes), binary.digest)
            assertNull(binary.bytes)
        }

    @Test fun metadataTombstonesAndUnknownCollectionNamesAreNotFiltered() =
        fixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_collection_records(user_id,collection,record_key,server_version,deleted,deleted_at) " +
                        "VALUES (?,'futureCollection','deleted-note',123,TRUE,456)",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.COLLECTION_RECORD, now = clock.now()))
            assertEquals(1L, page.count)
            assertEquals(listOf("futureCollection", "deleted-note"), page.items.single().key)
            assertEquals(JsonPrimitive(456), page.items.single().fields["deleted_at"])
        }

    @Test fun originalAuthorityAndCurrentLeaseAreRequiredForEveryPage() =
        fixture {
            assertNull(reader.page(held.copy(revision = held.revision + 1), IdentityAdoptionSourceKind.SYNC_CONTENT, now = clock.now()))
            assertNull(
                reader.page(
                    held.copy(binding = held.binding.copy(sourceDid = "did:web:other.example")),
                    IdentityAdoptionSourceKind.SYNC_CONTENT,
                    now = clock.now(),
                ),
            )
            clock.time += 121.seconds
            assertNull(reader.page(held, IdentityAdoptionSourceKind.SYNC_CONTENT, now = clock.now()))
        }

    @Test fun preparationPagesNeverClaimAStableHoldGeneration() =
        withIdentityAdoptionAuthorization {
            authorize()
            val reader = PostgreSQLIdentityAdoptionSourceInventory(source)
            val page = assertNotNull(reader.page(operation, IdentityAdoptionSourceKind.MEDIA, now = clock.now()))
            assertFalse(page.fenced)
            assertEquals(0L, page.generation)
        }

    @Test fun malformedPaginationAndConflictingExistingRepoAuthorityRefuse() =
        fixture {
            assertFailsWith<IdentityAdoptionStagingException> {
                reader.page(held, IdentityAdoptionSourceKind.SYNC_CONTENT, limit = 0, now = clock.now())
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                reader.page(held, IdentityAdoptionSourceKind.SYNC_ASSOCIATION, listOf("one"), now = clock.now())
            }
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_collection_states(user_id,repo_did,last_version) VALUES (?,'did:web:wrong.example',0)",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = clock.now())
            }
        }

    @Test fun personalInventoryIncludesRawRecordsDeletionsReferencesAndUnreferencedBlobs() =
        fixture {
            val space = SpaceRef.personal(operation.binding.sourceDid).uri
            source.blueskyTransaction {
                prepareStatement("INSERT INTO space_states(space_uri,author_did,account_id,last_seq) VALUES (?,?,?,2)").use {
                    it.setString(1, space)
                    it.setString(2, operation.binding.sourceDid)
                    it.setObject(3, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
                for (deleted in listOf(false, true)) {
                    prepareStatement(
                        "INSERT INTO space_records(space_uri,author_did,account_id,rkey,seq,cid,record,size_bytes,deleted,deleted_at) " +
                            "VALUES (?,?,?,?,?,?,?, ?,?,?)",
                    ).use {
                        it.setString(1, space)
                        it.setString(2, operation.binding.sourceDid)
                        it.setObject(3, operation.binding.accountId.toJavaUUID())
                        it.setString(4, if (deleted) "deleted" else "future")
                        it.setLong(5, if (deleted) 2 else 1)
                        it.setString(6, if (deleted) null else "future-cid")
                        it.setBytes(7, if (deleted) null else byteArrayOf(0xa0.toByte()))
                        it.setInt(8, if (deleted) 0 else 1)
                        it.setBoolean(9, deleted)
                        it.setTimestamp(10, if (deleted) clock.now().timestamp() else null)
                        it.executeUpdate()
                    }
                }
                for (cid in listOf("referenced", "retained")) {
                    prepareStatement(
                        "INSERT INTO space_blobs(space_uri,cid,account_id,mime_type,size_bytes,storage_path,created_at) " +
                            "VALUES (?,?,?,'application/octet-stream',100,?,?)",
                    ).use {
                        it.setString(1, space)
                        it.setString(2, cid)
                        it.setObject(3, operation.binding.accountId.toJavaUUID())
                        it.setString(4, "synthetic/$cid")
                        it.setTimestamp(5, clock.now().timestamp())
                        it.executeUpdate()
                    }
                }
                prepareStatement("INSERT INTO space_record_blobs(space_uri,author_did,rkey,cid) VALUES (?,?,'future','referenced')").use {
                    it.setString(1, space)
                    it.setString(2, operation.binding.sourceDid)
                    it.executeUpdate()
                }
            }
            val records = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SPACE_RECORD, now = clock.now()))
            assertEquals(2L, records.count)
            val live = records.items.single { it.key.last() == "future" }
            assertContentEquals(byteArrayOf(0xa0.toByte()), assertNotNull(live.binary).bytes)
            assertEquals(JsonPrimitive("future-cid"), live.fields["cid"])
            val deleted = records.items.single { it.key.last() == "deleted" }
            assertNull(deleted.binary)
            assertEquals(JsonPrimitive(true), deleted.fields["deleted"])
            assertTrue(deleted.fields["deleted_at"] != JsonNull)
            val blobs = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SPACE_BLOB, now = clock.now()))
            assertEquals(2L, blobs.count)
            assertEquals(setOf("referenced", "retained"), blobs.items.map { it.key.last() }.toSet())
            val refs = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SPACE_REFERENCE, now = clock.now()))
            assertEquals(1L, refs.count)
            assertEquals(listOf(space, operation.binding.sourceDid, "future", "referenced"), refs.items.single().key)
            val state = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SPACE_STATE, now = clock.now()))
            assertEquals(JsonPrimitive(2), state.items.single().fields["last_seq"])
        }

    @Test fun absentHistoricalBackupRelationIsExplicitInsteadOfInventedOrSilentlyOmitted() =
        fixture {
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SYNC_BACKUP, now = clock.now()))
            assertFalse(page.present)
            assertEquals(0L, page.count)
            source.blueskyTransaction {
                createStatement().use { statement ->
                    statement.executeQuery("SELECT to_regclass('sync_backups')").use {
                        assertTrue(it.next())
                        assertNull(it.getString(1))
                    }
                }
            }
        }

    @Test fun anExistingHistoricalBackupRelationRetainsItsFullManifestAndExtensions() =
        fixture {
            source.blueskyTransaction {
                createStatement().use {
                    it.execute(
                        "CREATE TABLE sync_backups(id UUID PRIMARY KEY,user_id UUID NOT NULL,manifest TEXT NOT NULL," +
                            "storage_path TEXT NOT NULL,encryption_version INTEGER,extra JSONB)",
                    )
                }
                prepareStatement(
                    "INSERT INTO sync_backups VALUES (?,?,?,'synthetic/backup',2,'{\"future\":null}'::jsonb)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    it.setString(3, "encrypted manifest 🌱")
                    it.executeUpdate()
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SYNC_BACKUP, now = clock.now()))
            assertTrue(page.present)
            assertEquals(1L, page.count)
            assertEquals(JsonPrimitive("encrypted manifest 🌱"), page.items.single().fields["manifest"])
            assertEquals(Json.parseToJsonElement("{\"future\":null}"), page.items.single().fields["extra"])
        }

    @Test fun unsupportedBinaryColumnsCannotDisappearFromTheInventory() =
        fixture {
            source.blueskyTransaction {
                createStatement().use { it.execute("ALTER TABLE sync_journals ADD COLUMN future_bytes BYTEA") }
            }
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    reader.page(held, IdentityAdoptionSourceKind.SYNC_JOURNAL, now = clock.now())
                }
            assertEquals(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE, failure.reason)
        }

    @Test fun oversizedRawBlocksRefuseInsteadOfReturningTruncatedEvidence() =
        fixture {
            blocks(1, 4_008_193)
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = clock.now())
                }
            assertEquals(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE, failure.reason)
        }

    @Test fun repositoryBlocksWithinTheAuditorProfileAreReturnedWithoutTruncation() =
        fixture {
            val body = ByteArray(3 * 1024 * 1024) { 1 }
            val cid =
                studio.hypertext.atproto.repo.Cid
                    .rawSha256(body)
                    .value
            source.blueskyTransaction {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES (?,?)").use {
                    it.setString(1, cid)
                    it.setBytes(2, body)
                    it.executeUpdate()
                }
                prepareStatement("INSERT INTO atproto_repo_block_links(repo_did,cid) VALUES (?,?)").use {
                    it.setString(1, operation.binding.sourceDid)
                    it.setString(2, cid)
                    it.executeUpdate()
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = clock.now()))
            val binary = assertNotNull(page.items.single().binary)
            val bytes = assertNotNull(binary.bytes)
            assertEquals(listOf(cid), page.items.single().key)
            assertEquals(3L * 1024 * 1024, binary.size)
            assertEquals(binary.size, bytes.size.toLong())
            assertEquals(hash(bytes), binary.digest)
            assertTrue(bytes.all { it == 1.toByte() })
            assertNull(page.next)
        }

    @Test fun byteBoundedPagesStillVisitEveryRawBlockExactlyOnce() =
        fixture {
            blocks(4, 2 * 1024 * 1024)
            val first = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, now = clock.now()))
            assertEquals(4L, first.count)
            assertTrue(first.items.size in 1..3)
            assertTrue(first.items.sumOf { assertNotNull(it.binary).bytes!!.size.toLong() } < 8L * 1024 * 1024)
            val second =
                assertNotNull(reader.page(held, IdentityAdoptionSourceKind.REPO_BLOCK, assertNotNull(first.next), now = clock.now()))
            assertEquals(4L, second.count)
            assertNull(second.next)
            assertEquals(listOf("block0", "block1", "block2", "block3"), (first.items + second.items).map { it.key.single() })
        }

    @Test fun validLargePrivateSpaceRecordsAreReturnedWithoutTruncation() =
        fixture {
            val ciphertext = ByteArray(33 * 65_536 + 16) { (it % 251).toByte() }
            val record =
                buildJsonObject {
                    put("$" + "type", "app.logdate.crypto.sealed")
                    put("version", 1)
                    put("keyId", "synthetic-large-record")
                    putJsonObject("nonce") { put("$" + "bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
                    putJsonObject("ciphertext") { put("$" + "bytes", Base64.getEncoder().encodeToString(ciphertext)) }
                }
            val (cid, bytes) = recordCid(record)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO space_records(space_uri,author_did,rkey,account_id,seq,cid,record,size_bytes) VALUES (?,?,?,?,1,?,?,?)",
                ).use {
                    it.setString(1, SpaceRef.personal(operation.binding.sourceDid).uri)
                    it.setString(2, operation.binding.sourceDid)
                    it.setString(3, "large-note")
                    it.setObject(4, operation.binding.accountId.toJavaUUID())
                    it.setString(5, cid)
                    it.setBytes(6, bytes)
                    it.setInt(7, bytes.size)
                    it.executeUpdate()
                }
            }
            val page = assertNotNull(reader.page(held, IdentityAdoptionSourceKind.SPACE_RECORD, now = clock.now()))
            val binary = assertNotNull(page.items.single().binary)
            assertEquals(bytes.size.toLong(), binary.size)
            assertEquals(hash(bytes), binary.digest)
            assertContentEquals(bytes, binary.bytes)
        }

    private suspend fun Setup.blocks(
        count: Int,
        size: Int,
    ) {
        source.blueskyTransaction {
            for (index in 0 until count) {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES (?,decode(repeat('01',?),'hex'))").use {
                    it.setString(1, "block$index")
                    it.setInt(2, size)
                    it.executeUpdate()
                }
                prepareStatement("INSERT INTO atproto_repo_block_links(repo_did,cid) VALUES (?,?)").use {
                    it.setString(1, operation.binding.sourceDid)
                    it.setString(2, "block$index")
                    it.executeUpdate()
                }
            }
        }
    }

    private fun fixture(block: suspend Setup.() -> Unit) =
        withIdentityAdoptionAuthorization {
            authorize()
            val transition =
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    clock.now(),
                )
            val held = (transition as IdentityAdoptionOutcome.Accepted).value
            Setup(this, held).block()
        }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
        val held: IdentityAdoptionOperation,
    ) {
        val source get() = auth.source
        val clock get() = auth.clock
        val operation get() = auth.operation
        val reader = PostgreSQLIdentityAdoptionSourceInventory(source)
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
