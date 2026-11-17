package app.logdate.server.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for UUID interoperability extension functions.
 *
 * Validates the bidirectional conversion between [java.util.UUID] and the Kotlin-native
 * [kotlin.uuid.Uuid] (Experimental). These tests ensure that round-tripping maintains
 * identity.
 */
@OptIn(ExperimentalUuidApi::class)
class UuidExtensionsTest {
    @Test
    fun `uuid conversion helpers round-trip`() {
        val kotlinUuid = Uuid.random()
        val javaUuid = kotlinUuid.toJavaUUID()

        assertEquals(kotlinUuid, javaUuid.toKotlinUuid())
    }
}
