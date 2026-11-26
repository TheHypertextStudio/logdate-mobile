package app.logdate.client.database

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the data export honest about what it covers.
 *
 * Every table in the latest Room schema must be classified here as exported, planned for export,
 * derived, or internal bookkeeping. Adding a table fails this test until the new data has been
 * considered for the export, so a new kind of user data cannot silently miss the archive.
 */
class ExportCoverageTest {
    private enum class Coverage {
        /** Written to the export archive today. */
        EXPORTED,

        /** User data the export must cover; not written yet. */
        PLANNED,

        /** Rebuildable from other data or specific to this device; intentionally left out. */
        DERIVED,

        /** Sync, search or storage bookkeeping with no meaning outside the app. */
        BOOKKEEPING,
    }

    private class Classification(
        val coverage: Coverage,
        val reason: String,
    )

    private fun exported() = Classification(Coverage.EXPORTED, "")

    private fun planned(reason: String) = Classification(Coverage.PLANNED, reason)

    private fun derived(reason: String) = Classification(Coverage.DERIVED, reason)

    private fun bookkeeping(reason: String) = Classification(Coverage.BOOKKEEPING, reason)

    private val classifications: Map<String, Classification> =
        mapOf(
            "text_notes" to exported(),
            "image_notes" to exported(),
            "video_notes" to exported(),
            "audio_notes" to exported(),
            "media_captions" to exported(),
            "journals" to exported(),
            "journal_content_links" to exported(),
            "journal_merges" to bookkeeping("Scoped merge redirects and upload intents; canonical content and memberships are exported."),
            "location_logs" to exported(),
            "location_activity" to planned("Movement evidence; encrypted history sync implemented, archive export pending."),
            "history_records" to planned("Places, corrections, and history evidence; encrypted sync implemented, archive export pending."),
            "history_cursors" to bookkeeping("Encrypted location history sync progress markers."),
            "user_places" to exported(),
            "transcriptions" to planned("Transcripts of audio notes, written per note through TranscriptionRepository."),
            "transcription_segments" to planned("Timed segments of a transcript; part of the transcript document."),
            "audio_tags" to planned("Ambient sounds detected in audio notes."),
            "health_snapshots" to planned("Vitals recorded with a note; needs a repository the export can read."),
            "events" to planned("Events the user edited or linked notes to."),
            "event_note_links" to planned("Which notes belong to which event."),
            "people" to planned("People the user added or confirmed."),
            "person_links" to planned("Which entries and events mention which person."),
            "person_resolution_decisions" to planned("The user's merge and suppress decisions about people."),
            "rewinds" to planned("Generated period recaps that are costly to regenerate."),
            "rewind_text_content" to planned("Text blocks of a rewind."),
            "rewind_image_content" to planned("Image blocks of a rewind."),
            "rewind_video_content" to planned("Video blocks of a rewind."),
            "rewind_prompt_responses" to planned("The user's own written replies to rewind prompts."),
            "postcards" to planned("Postcards the user designed."),
            "stickers" to planned("Stickers the user extracted from photos."),
            "places" to derived("Federation-style places; nothing writes them yet, so note place ids are never set."),
            "indexed_media_images" to derived("Index of the device photo library; specific to this device."),
            "indexed_media_videos" to derived("Index of the device video library; specific to this device."),
            "media_exif_metadata" to derived("EXIF cache for the device library; exported images contain their own EXIF."),
            "inferred_person_clusters" to derived("Name mentions the app inferred and the user has not confirmed."),
            "inferred_person_evidence" to derived("Evidence behind an inferred person cluster."),
            "place_resolution_cache" to derived("Rebuildable local cache of external and coarse place lookups by grid cell."),
            "journal_notes" to bookkeeping("Legacy link table replaced by journal_content_links; nothing reads it."),
            "media_images" to bookkeeping("Legacy media table; nothing reads it."),
            "sync_cursors" to bookkeeping("Sync progress markers."),
            "pending_uploads" to bookkeeping("Sync upload queue."),
            "sync_download_inbox" to bookkeeping("Encrypted remote work awaiting application; not local journal content."),
            "sync_download_checkpoints" to bookkeeping("Account-scoped download cursors and replay progress."),
            "peer_delivery" to bookkeeping("Where each record stands between the phone and the watch."),
            "sync_space_records" to bookkeeping("The last known version of each personal-space record, for swap checks."),
            "marked_moments" to planned("Moments marked on the watch; the export covers them once marked moments ship."),
            "search_index_metadata" to bookkeeping("Search index version."),
            "storage_metadata" to bookkeeping("Storage quota footprint."),
            "user_devices" to bookkeeping("Registry of devices signed in to the account."),
            "rewind_generation_requests" to bookkeeping("State of rewind generation jobs."),
        )

    @Test
    fun everyTableInTheLatestSchemaIsClassified() {
        val unclassified = schemaTables() - classifications.keys

        assertTrue(
            unclassified.isEmpty(),
            "Tables missing from the export coverage list: $unclassified. Decide whether each contains user data the " +
                "export must include, then add it to ExportCoverageTest with the matching classification.",
        )
    }

    @Test
    fun noClassificationNamesATableThatIsGone() {
        val stale = classifications.keys - schemaTables()

        assertTrue(stale.isEmpty(), "Classified tables that are not in the latest schema: $stale")
    }

    @Test
    fun everyTableThatIsNotExportedExplainsWhy() {
        val unexplained =
            classifications
                .filterValues { it.coverage != Coverage.EXPORTED }
                .filterValues { it.reason.isBlank() }
                .keys

        assertEquals(emptySet(), unexplained, "Tables left out of the export need a reason")
    }

    private fun schemaTables(): Set<String> {
        val schemaDirectory = File("schemas/app.logdate.client.database.LogDateDatabase")
        val latest =
            schemaDirectory
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .maxByOrNull { it.nameWithoutExtension.toInt() }
        checkNotNull(latest) { "No Room schema found in ${schemaDirectory.absolutePath}" }

        return TABLE_NAME
            .findAll(latest.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private companion object {
        val TABLE_NAME = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"")
    }
}
