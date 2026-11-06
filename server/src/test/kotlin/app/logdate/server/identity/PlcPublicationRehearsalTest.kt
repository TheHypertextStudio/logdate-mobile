package app.logdate.server.identity

import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlcPublicationRehearsalTest {
    private val key =
        "did:key:" + EcKeySupport.encodePublicKeyMultibase(EcKeySupport.generateKeyPair(EcCurve.P256).public as ECPublicKey, EcCurve.P256)

    private val approved =
        mapOf(
            "LOGDATE_ENV" to "test",
            "LOGDATE_PLC_PUBLICATION_REHEARSAL" to "true",
            "ATPROTO_PLC_DIRECTORY_URL" to "http://127.0.0.1:35483",
            "LOGDATE_PLC_REHEARSAL_ROTATION_KEY" to key,
        )

    @Test
    fun `a shared database cannot be used by the rehearsal`() {
        assertTrue(isPlcRehearsalDatabase("jdbc:postgresql://127.0.0.1:6543/logdate_plc_rehearsal_case"))
        assertFalse(isPlcRehearsalDatabase("jdbc:postgresql://127.0.0.1:6543/logdate"))
        assertFalse(isPlcRehearsalDatabase("jdbc:postgresql://cloud.example:6543/logdate_plc_rehearsal_case"))
        assertFalse(isPlcRehearsalDatabase("jdbc:postgresql://127.0.0.1:6543/logdate_plc_rehearsal_case?socketFactory=override"))
    }

    @Test
    fun `only an explicit local profile with a valid key and exact loopback directory can rehearse`() {
        assertEquals(
            PlcPublicationRehearsal("http://127.0.0.1:35483", key),
            PlcPublicationRehearsal.read(approved::get),
        )
        for (override in listOf(
            "LOGDATE_ENV" to "production",
            "LOGDATE_ENV" to "typo",
            "LOGDATE_PLC_PUBLICATION_REHEARSAL" to "false",
            "ATPROTO_PLC_PUBLISH_ENABLED" to "true",
            "ATPROTO_RELAY_HOSTS" to "relay.example.com",
            "ATPROTO_PLC_DIRECTORY_URL" to "https://plc.directory",
            "ATPROTO_PLC_DIRECTORY_URL" to "http://localhost:35483",
            "ATPROTO_PLC_DIRECTORY_URL" to "http://127.0.0.1:35483/redirect",
            "ATPROTO_PLC_DIRECTORY_URL" to "http://127.0.0.1:35483/?target=https://plc.directory",
            "ATPROTO_PLC_DIRECTORY_URL" to "http://127.0.0.1:35483@example.com",
            "LOGDATE_PLC_REHEARSAL_ROTATION_KEY" to "did:key:broken",
        )) {
            assertNull(PlcPublicationRehearsal.read((approved + override)::get), override.first)
        }
    }
}
