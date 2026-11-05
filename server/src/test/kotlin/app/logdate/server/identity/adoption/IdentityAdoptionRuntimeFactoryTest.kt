package app.logdate.server.identity.adoption

import io.mockk.mockk
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class IdentityAdoptionRuntimeFactoryTest {
    @Test fun disabledRuntimeNeverTouchesStorageOrAllocatesAWorker() {
        assertNull(
            createIdentityAdoptionRuntime(
                false,
                null,
                { null },
                { error("source accessed") },
                { error("schema accessed") },
                { _, _ -> error("runtime allocated") },
            ),
        )
    }

    @Test fun enabledRuntimeRequiresEveryPrerequisiteBeforeBuilding() {
        val flags =
            mapOf(
                "LOGDATE_IDENTITY_ADOPTION_ENABLED" to "true",
                "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED" to "true",
                "LOGDATE_PERSONAL_SPACE_ENABLED" to "true",
            )
        for (missing in listOf("database", "oauth", "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED", "LOGDATE_PERSONAL_SPACE_ENABLED")) {
            assertFailsWith<IllegalArgumentException> {
                createIdentityAdoptionRuntime(
                    missing != "database",
                    if (missing == "oauth") null else mockk(),
                    (flags - missing)::get,
                    { mockk() },
                    {},
                    { _, _ -> error("runtime allocated without prerequisites") },
                )
            }
        }
    }

    @Test fun schemaIsValidatedBeforeBuildingTheSingleProtectedRuntime() {
        val source = mockk<DataSource>()
        val oauth = mockk<app.logdate.server.bluesky.BlueskyOAuthRuntime>()
        val runtime =
            assertNotNull(
                createIdentityAdoptionRuntime(
                    true,
                    oauth,
                    { "true" },
                    { source },
                    { assertSame(source, it) },
                    { database, borrowed ->
                        assertSame(source, database)
                        assertSame(oauth, borrowed)
                        IdentityAdoptionRuntime(
                            mockk(),
                            mockk(),
                            mockk(),
                            mockk(),
                            mockk(),
                            mockk(),
                            mockk(),
                            IdentityAdoptionMaintenance({ emptyList() }, { true }),
                        )
                    },
                ),
            )
        runtime.close()
        assertFailsWith<IllegalStateException> {
            createIdentityAdoptionRuntime(true, oauth, { "true" }, { source }, { error("schema absent") }) { _, _ ->
                error("runtime allocated after schema failure")
            }
        }
    }
}
