package app.logdate.client.domain.restore.archive3

import app.logdate.client.domain.export.archive.ArchivePathAllocator
import app.logdate.client.domain.export.archive.ArchiveSnapshot
import app.logdate.client.domain.export.archive.MediaFileNamer
import app.logdate.client.domain.export.archive.MediaReferences
import app.logdate.client.domain.export.archive.MediaResolution
import app.logdate.client.domain.export.archive.MediaResolver
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.domain.export.archive.mediaRequests
import app.logdate.client.domain.export.archive.toArchiveDraft
import app.logdate.client.domain.export.archive.toArchiveNote
import app.logdate.client.domain.export.archive.toArchivePlace
import app.logdate.client.domain.export.archive.toArchiveProfile
import app.logdate.client.domain.export.archive.toArchiveSample
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Place
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.Source
import kotlin.uuid.Uuid

/** A source original selected by the frozen Android journal snapshot. */
data class NativeArchive3Original(
    val reference: String,
    val bytes: Long,
    val sha256: String,
    val open: () -> Source,
)

/** Reads only immutable, already-censused source content; the callback owns the source fence. */
data class NativeArchive3SnapshotInput(
    val exportedAt: String,
    val journals: List<Journal>,
    val notes: List<JournalNote>,
    val noteSourceLocationMetadata: Map<Uuid, String> = emptyMap(),
    val journalIdsByNote: Map<Uuid, List<Uuid>>,
    val drafts: List<EditorDraft>,
    val profile: LogDateProfile,
    val places: List<Place.UserDefined>,
    val placeSourceTimes: Map<Uuid, Pair<Long, Long>> = emptyMap(),
    val semanticPlaces: JsonArray = JsonArray(emptyList()),
    val locationHistory: List<LocationHistoryItem>,
    val originals: List<NativeArchive3Original>,
    val unsupportedPopulatedCategories: Set<String>,
    val validateContext: () -> Unit,
)

/** Uses the established archive projections while requiring every original and category to survive. */
object NativeArchive3SnapshotWriter {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    suspend fun write(
        input: NativeArchive3SnapshotInput,
        container: StagingDirectoryArchiveContainer,
    ) {
        try {
            require(input.unsupportedPopulatedCategories.isEmpty()) {
                "Native transfer cannot retain: ${input.unsupportedPopulatedCategories.sorted().joinToString()}"
            }
            val noteIdCounts = input.notes.groupingBy { it.uid }.eachCount()
            require(input.noteSourceLocationMetadata.keys.all { noteIdCounts[it] == 1 }) {
                "Native transfer note location metadata has no matching source note"
            }
            require(input.places.map { it.id }.toSet() == input.placeSourceTimes.keys && input.places.size == input.placeSourceTimes.size) {
                "Native transfer place timestamps are incomplete"
            }
            input.validateContext()
            val sources = input.originals.associateBy { it.reference }
            require(sources.size == input.originals.size)
            val snapshot =
                ArchiveSnapshot(
                    input.journals,
                    input.notes,
                    input.journalIdsByNote,
                    input.drafts,
                    input.profile,
                    input.places,
                    input.locationHistory,
                    null,
                    "3.0",
                    emptySet(),
                )
            val resolution =
                MediaResolver(MediaSourceOpener { reference -> sources[reference]?.open?.invoke() }, MediaFileNamer(ArchivePathAllocator()))
                    .resolve(snapshot.mediaRequests(TimeZone.UTC))
            require(!resolution.hasOmittedFiles && resolution.files.size == sources.size) { "Native original is unavailable" }
            NativeArchive3Writer.write(exportSource(input, sources, resolution), container)
        } catch (failure: Throwable) {
            runCatching { container.discard() }
            throw failure
        }
    }

    private fun exportSource(
        input: NativeArchive3SnapshotInput,
        sources: Map<String, NativeArchive3Original>,
        resolution: MediaResolution,
    ): NativeArchive3ExportSource {
        val journalIds = input.journals.map { it.id }.toSet()
        require(
            input.journalIdsByNote.values
                .flatten()
                .all { it in journalIds },
        )
        val media = MediaReferences(resolution)
        return NativeArchive3ExportSource(
            exportedAt = input.exportedAt,
            journals =
                input.journals.map {
                    NativeArchive3JournalRecord(
                        it.id.toString(),
                        it.title,
                        it.description,
                        it.isFavorited,
                        it.created.toString(),
                        it.lastUpdated.toString(),
                    )
                },
            notes = archiveNotes(input, media),
            drafts = JsonArray(input.drafts.map { json.encodeToJsonElement(it.toArchiveDraft(media)) }),
            media =
                resolution.files.map { file ->
                    val original = sources.getValue(file.reference)
                    NativeArchive3MediaSource(file.path, file.type.mimeType, original.bytes, original.sha256, original.open)
                },
            places = archivePlaces(input),
            semanticPlaces = input.semanticPlaces,
            profile = input.profile.toArchiveProfile()?.let { json.encodeToJsonElement(it) as JsonObject },
            locationSamples = archiveSamples(input.locationHistory),
            unsupportedPopulatedCategories = input.unsupportedPopulatedCategories,
            validateContext = input.validateContext,
        )
    }

    private fun archiveNotes(
        input: NativeArchive3SnapshotInput,
        media: MediaReferences,
    ): JsonArray =
        JsonArray(
            input.notes.map { note ->
                val archive = note.toArchiveNote(input.journalIdsByNote[note.uid].orEmpty(), media)
                val payload = json.encodeToJsonElement(archive) as JsonObject
                JsonObject(
                    buildMap {
                        putAll(payload)
                        put("nativePrimaryJournalId", JsonPrimitive(input.journalIdsByNote[note.uid]?.firstOrNull()?.toString()))
                        input.noteSourceLocationMetadata[note.uid]?.let { put("nativeAndroidLocationMetadata", JsonPrimitive(it)) }
                    },
                )
            },
        )

    private fun archivePlaces(input: NativeArchive3SnapshotInput): JsonArray =
        JsonArray(
            input.places.map { place ->
                val payload = json.encodeToJsonElement(place.toArchivePlace()) as JsonObject
                val (createdAt, updatedAt) = input.placeSourceTimes.getValue(place.id)
                JsonObject(
                    payload +
                        mapOf(
                            "nativeCreatedAtMilliseconds" to JsonPrimitive(createdAt),
                            "nativeUpdatedAtMilliseconds" to JsonPrimitive(updatedAt),
                        ),
                )
            },
        )

    private fun archiveSamples(history: List<LocationHistoryItem>): List<JsonObject> =
        history.map { item ->
            val projected = json.encodeToJsonElement(item.toArchiveSample()) as JsonObject
            JsonObject(
                projected +
                    buildMap {
                        put("sampleId", JsonPrimitive(item.sampleId))
                        put("userId", JsonPrimitive(item.userId))
                        put("deviceId", JsonPrimitive(item.deviceId))
                        item.activityType?.let { put("activityType", JsonPrimitive(it)) }
                        item.timeZoneId?.let { put("timeZoneId", JsonPrimitive(it)) }
                    },
            )
        }
}
