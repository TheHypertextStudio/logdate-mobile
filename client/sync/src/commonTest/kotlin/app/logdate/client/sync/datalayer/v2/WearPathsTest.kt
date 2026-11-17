package app.logdate.client.sync.datalayer.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests [WearPaths], the version 2 Data Layer paths both devices build and parse.
 */
class WearPathsTest {
    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `a record's media channel reads back and is not a record path`() {
        val path = WearPaths.recordMedia(RecordKind.NOTE, id)

        assertEquals("/logdate/v2/w2p/media/note/$id", path)
        assertEquals(RecordPath(RecordKind.NOTE, id), WearPaths.parseRecordMedia(path))
        assertNull(WearPaths.parseWatchRecord(path))
    }

    @Test
    fun `a window item path names its kind and id`() {
        assertEquals(
            "/logdate/v2/p2w/window/note/550e8400-e29b-41d4-a716-446655440000",
            WearPaths.windowItem(RecordKind.NOTE, id),
        )
    }

    @Test
    fun `record paths read back as the kind and id they were built from`() {
        RecordKind.entries.forEach { kind ->
            assertEquals(RecordPath(kind, id), WearPaths.parseWindowItem(WearPaths.windowItem(kind, id)))
            assertEquals(RecordPath(kind, id), WearPaths.parseWindowTombstone(WearPaths.windowTombstone(kind, id)))
            assertEquals(RecordPath(kind, id), WearPaths.parseWatchRecord(WearPaths.watchRecord(kind, id)))
        }
    }

    @Test
    fun `a path of another family or an unknown kind does not parse`() {
        assertNull(WearPaths.parseWindowItem(WearPaths.windowTombstone(RecordKind.NOTE, id)))
        assertNull(WearPaths.parseWindowItem("/logdate/v2/p2w/window/postcard/$id"))
        assertNull(WearPaths.parseWindowItem("/logdate/v2/p2w/window/note/not-a-uuid"))
        assertNull(WearPaths.parseWindowItem("/logdate/v2/p2w/window/note/$id/extra"))
    }

    @Test
    fun `no version 2 path reaches a version 1 listener`() {
        val v1Prefixes =
            listOf("/logdate/notes", "/logdate/journals", "/logdate/associations", "/logdate/health", "/logdate/camera", "/logdate/sync")
        val v2Paths =
            listOf(
                WearPaths.STATE,
                WearPaths.recordMedia(RecordKind.NOTE, id),
                WearPaths.RECEIPTS,
                WearPaths.RECEIPTS_PUSH,
                WearPaths.WINDOW_RESYNC,
                WearPaths.LINK_OFFER,
                WearPaths.LINK_BUNDLE,
                WearPaths.LIVE_RECORDING,
                WearPaths.LIVE_RECORDING_COMMAND,
                WearPaths.LIVE_PLAYBACK,
                WearPaths.LIVE_PLAYBACK_COMMAND,
                WearPaths.REMINDERS_DELIVER,
                WearPaths.REMINDERS_CLAIM,
                WearPaths.PRESENCE,
                WearPaths.DIAG_PING,
                WearPaths.FAVORITES_SET,
                WearPaths.windowItem(RecordKind.NOTE, id),
                WearPaths.windowTombstone(RecordKind.NOTE, id),
                WearPaths.watchRecord(RecordKind.NOTE, id),
                WearPaths.bodyChannel("ab".repeat(32)),
            )

        v2Paths.forEach { path -> assertTrue(v1Prefixes.none { path.startsWith(it) }, path) }
    }

    @Test
    fun `the request and live paths are the ones the master plan names`() {
        assertEquals("/logdate/state", WearPaths.STATE)
        assertEquals("/logdate/link/offer", WearPaths.LINK_OFFER)
        assertEquals("/logdate/link/bundle", WearPaths.LINK_BUNDLE)
        assertEquals("/logdate/live/recording", WearPaths.LIVE_RECORDING)
        assertEquals("/logdate/live/recording/command", WearPaths.LIVE_RECORDING_COMMAND)
        assertEquals("/logdate/live/playback", WearPaths.LIVE_PLAYBACK)
        assertEquals("/logdate/live/playback/command", WearPaths.LIVE_PLAYBACK_COMMAND)
        assertEquals("/logdate/reminders/deliver", WearPaths.REMINDERS_DELIVER)
        assertEquals("/logdate/reminders/claim", WearPaths.REMINDERS_CLAIM)
        assertEquals("/logdate/presence", WearPaths.PRESENCE)
        assertEquals("/logdate/diag/ping", WearPaths.DIAG_PING)
        assertEquals("/logdate/favorites/set", WearPaths.FAVORITES_SET)
    }

    @Test
    fun `a body channel needs a lowercase sha256`() {
        assertEquals("/logdate/v2/body/${"0f".repeat(32)}", WearPaths.bodyChannel("0f".repeat(32)))
        assertFailsWith<IllegalArgumentException> { WearPaths.bodyChannel("0F".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { WearPaths.bodyChannel("abc") }
    }

    @Test
    fun `the capability and feature names are the published ones`() {
        assertEquals("logdate_phone_sync_v2", WearProtocol.PHONE_CAPABILITY_V2)
        assertEquals("logdate_watch_sync_v2", WearProtocol.WATCH_CAPABILITY_V2)
        assertEquals(
            setOf(
                "receipts.v1",
                "receipts.operations.v1",
                "window.v1",
                "media.range.v1",
                "moments.v1",
                "live.v1",
                "reminders.v1",
                "reminders.supplements.v1",
                "reminders.activity.v1",
                "link.cloud.v1",
                "phone.camera.v1",
                "phone.playback.v1",
                "phone.settingsLink.v1",
                "prefs.v1",
            ),
            WearFeatures.ALL,
        )
    }
}
