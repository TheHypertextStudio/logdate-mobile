@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceQuery
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

class IdentityAdoptionSourceAccessPostgresTest {
    @Test fun onlyAFreshSignedReadCanRetrieveTheExactSourcePage() =
        withGenericAdoption {
            hold()
            val service = PostgreSQLIdentityAdoptionSourceAccess(auth.source, auth.clock)
            val query = IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT)
            val challenge = assertNotNull(service.issue(current, query))
            assertEquals(IdentityAdoptionAction.READ_STAGED, challenge.action)
            assertEquals(sourceQueryDigest(query), challenge.bodyDigest)
            val consumed = auth.countConsumed()
            val page = assertNotNull(service.read(current, query, challenge, auth.actionSignature(challenge, current)))
            assertEquals(current.id, page.operationId)
            assertEquals(current.revision, page.revision)
            assertEquals(query.kind, page.kind)
            assertEquals(1L, page.count)
            assertEquals(listOf("legacy-note"), page.items.single().key)
            assertTrue(page.fenced)
            assertTrue(page.generation > 0)
            assertEquals(consumed + 1, auth.countConsumed())
            assertNull(service.read(current, query, challenge, auth.actionSignature(challenge, current)))
        }

    @Test fun sourceReadsDoNotRequirePausingOrdinaryWritesDuringPreparation() =
        withGenericAdoption {
            val service = PostgreSQLIdentityAdoptionSourceAccess(auth.source, auth.clock)
            val query = IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT)
            val challenge = assertNotNull(service.issue(current, query))
            val page = assertNotNull(service.read(current, query, challenge, auth.actionSignature(challenge, current)))
            assertEquals(false, page.fenced)
            assertEquals(0L, page.generation)
            assertEquals(1L, page.count)
        }

    @Test fun anotherDeviceSignatureAndChangedQueryCannotConsumeReadAuthority() =
        withGenericAdoption {
            hold()
            val service = PostgreSQLIdentityAdoptionSourceAccess(auth.source, auth.clock)
            val query = IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT)
            val challenge = assertNotNull(service.issue(current, query))
            val consumed = auth.countConsumed()
            val invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
            assertNull(service.read(current, query, challenge, invalid))
            assertNull(
                service.read(
                    current,
                    query.copy(kind = IdentityAdoptionSourceKind.SYNC_JOURNAL),
                    challenge,
                    auth.actionSignature(challenge, current),
                ),
            )
            assertNull(
                service.read(
                    current.copy(binding = current.binding.copy(deviceId = "another")),
                    query,
                    challenge,
                    auth.actionSignature(challenge, current),
                ),
            )
            assertEquals(consumed, auth.countConsumed())
            assertNotNull(service.read(current, query, challenge, auth.actionSignature(challenge, current)))
        }

    @Test fun oversizedLimitsAndWrongCursorArityNeverAllocateChallenges() =
        withGenericAdoption {
            val service = PostgreSQLIdentityAdoptionSourceAccess(auth.source, auth.clock)
            for (query in listOf(
                IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT, limit = 33),
                IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT, after = listOf("a", "b")),
            )) {
                assertFailsWith<IdentityAdoptionStagingException> { service.issue(current, query) }
            }
        }

    @Test fun leaseExpiryDuringAReadRollsBackChallengeConsumption() =
        withGenericAdoption {
            hold()
            val query = IdentityAdoptionSourceQuery(IdentityAdoptionSourceKind.SYNC_CONTENT)
            val stable = PostgreSQLIdentityAdoptionSourceAccess(auth.source, auth.clock)
            val challenge = assertNotNull(stable.issue(current, query))
            val start = auth.clock.now()
            val observed =
                IdentityAdoptionAfterStatementFixture(auth.source, "SELECT count(*) FROM sync_content", "executeQuery") {
                    auth.clock.time = start + 121.seconds
                }
            val consumed = auth.countConsumed()
            assertFailsWith<IdentityAdoptionStagingException> {
                PostgreSQLIdentityAdoptionSourceAccess(
                    observed,
                    auth.clock,
                ).read(current, query, challenge, auth.actionSignature(challenge, current))
            }
            assertTrue(observed.executed)
            assertEquals(consumed, auth.countConsumed())
        }
}

internal fun sourceQueryDigest(query: IdentityAdoptionSourceQuery): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.source-page")
                put("kind", query.kind.name)
                put("after", query.after?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                put("limit", query.limit)
            },
        ),
    )
