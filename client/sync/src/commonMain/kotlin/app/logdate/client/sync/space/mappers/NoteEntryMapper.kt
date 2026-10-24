package app.logdate.client.sync.space.mappers

import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.space.SpaceMediaResolver
import app.logdate.client.sync.space.SpaceRecordMapper
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.blobCidFor
import app.logdate.client.sync.space.canonicalRecordKey
import app.logdate.client.sync.space.centimetersToMeters
import app.logdate.client.sync.space.decodeRecord
import app.logdate.client.sync.space.encodeRecord
import app.logdate.client.sync.space.mediaFor
import app.logdate.client.sync.space.parseDatetime
import app.logdate.client.sync.space.parseDecimal
import app.logdate.client.sync.space.parseUuidKey
import app.logdate.client.sync.space.recordRef
import app.logdate.client.sync.space.sameRecordKey
import app.logdate.client.sync.space.toCentimeters
import app.logdate.client.sync.space.toDatetime
import app.logdate.client.sync.space.toDecimalString
import app.logdate.lexicon.app.logdate.DefsLocation
import app.logdate.lexicon.app.logdate.DefsMedia
import app.logdate.lexicon.app.logdate.DefsPlaceDetails
import app.logdate.lexicon.app.logdate.DefsRecordRef
import app.logdate.lexicon.app.logdate.journal.Entry
import app.logdate.lexicon.app.logdate.journal.EntryAudioBlock
import app.logdate.lexicon.app.logdate.journal.EntryBlocksItem
import app.logdate.lexicon.app.logdate.journal.EntryHandwritingBlock
import app.logdate.lexicon.app.logdate.journal.EntryImageBlock
import app.logdate.lexicon.app.logdate.journal.EntryTextBlock
import app.logdate.lexicon.app.logdate.journal.EntryVideoBlock
import app.logdate.lexicon.app.logdate.journal.Transcript
import app.logdate.lexicon.app.logdate.place.Place
import app.logdate.lexicon.community.lexicon.location.Geo
import app.logdate.shared.model.HandwritingDocument
import app.logdate.shared.model.PhotoPresentation
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Converts notes to and from `app.logdate.journal.entry` records.
 *
 * Each note becomes one entry with a single block. The entry's record key and the block's id are
 * both the note's id. An audio note's transcript is its own record (see [NoteTranscriptMapper]);
 * the entry refers to it, and [fromRecord] returns the note without it.
 *
 * A location's place is written as the place's key plus a copy of its name and center, so the
 * note reads back the same on a device that doesn't have the place. A record with a place and no
 * copy reads back as a place with only its id, and is written back without the copy. A place with
 * a name but no center, or a center but no name, can't be written as a copy and is refused.
 *
 * A note whose file no device has (an empty media reference, or an unavailable one from a restore)
 * becomes an entry whose block has no media. It reads back with
 * [UnavailableMediaReferences.archive] for the note, so the app shows the file as missing.
 *
 * What a record can't hold:
 * - `syncVersion` is the old sync's version, not part of the note: the transport applies the
 *   record's position in the space's change log instead, so [fromRecord] returns 0.
 * - An audio note's `transcription` is the editor's working text, which sync never sends.
 * - Accuracy is stored in whole centimeters, rounded to the nearest one, and 0 if it was negative.
 *   An accuracy with no coordinates to describe is left out.
 * - Text written as `plain` reads as the equivalent Markdown: every character that Markdown would
 *   act on is escaped and each line break is a hard break, so it shows as it was written. It is
 *   written back as Markdown, so `plain` round-trips as equivalent `markdown`. A line that starts
 *   with spaces or a tab is refused, because Markdown drops leading whitespace and the note would
 *   lose its indentation.
 *
 * A media file's text alternative and aspect ratio travel in its [SpaceMediaReference], so they
 * are written back as they arrived.
 *
 * @param media connects the note's file with its uploaded blob.
 */
class NoteEntryMapper(
    private val media: SpaceMediaResolver = SpaceMediaResolver.Default,
) : SpaceRecordMapper<JournalNote> {
    override val collection: String = Entry.TYPE

    override fun rkeyOf(value: JournalNote): String = canonicalRecordKey(value.uid.toString())

    override fun toRecord(value: JournalNote): JsonObject {
        val owner = "$collection/${value.uid}"
        val entry =
            Entry(
                blocks = listOf(blockOf(value, owner)),
                location = value.location?.let { locationOf(it, owner) },
                timeZone = value.timeZoneId,
                capturedOn = value.capturedOn?.let(::capturedOnValue),
                capturedDuring = value.capturedDuring?.let(::capturedDuringValue),
                createdAt = value.creationTimestamp.toDatetime("$owner createdAt"),
                updatedAt = value.lastUpdated.toDatetime("$owner updatedAt"),
            )
        return encodeRecord(Entry.serializer(), entry)
    }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): JournalNote {
        val owner = "$collection/$rkey"
        val uid = parseUuidKey(collection, rkey)
        val entry = decodeRecord(Entry.serializer(), collection, rkey, record)
        val block =
            entry.blocks.singleOrNull()
                ?: throw SpaceRecordMappingException("$owner: an entry with ${entry.blocks.size} blocks isn't one note")
        val created = parseDatetime("$owner createdAt", entry.createdAt)
        val context =
            NoteContext(
                uid = uid,
                created = created,
                updated = entry.updatedAt?.let { parseDatetime("$owner updatedAt", it) } ?: created,
                location = entry.location?.let { noteLocationOf(it, owner) },
                timeZoneId = entry.timeZone,
                capturedOn = CaptureDevice.fromWireName(entry.capturedOn),
                capturedDuring = entry.capturedDuring?.let(::captureActivityOf),
            )
        return noteOf(block, context, owner)
    }

    override fun blobCidsOf(value: JournalNote): List<String> {
        val mediaRef = value.mediaRefOrNull()?.takeUnless(::isUnavailable) ?: return emptyList()
        return listOf(media.blobCidFor(mediaRef, "$collection/${value.uid}"))
    }

    private fun blockOf(
        note: JournalNote,
        owner: String,
    ): EntryBlocksItem {
        val id = note.uid.toString()
        return when (note) {
            is JournalNote.Handwriting -> {
                EntryBlocksItem.HandwritingBlock(
                    EntryHandwritingBlock(id = id, document = note.unreadableContent ?: note.document.encode()),
                )
            }

            is JournalNote.Text -> {
                EntryBlocksItem.TextBlock(EntryTextBlock(id = id, text = note.content))
            }

            is JournalNote.Image -> {
                EntryBlocksItem.ImageBlock(
                    EntryImageBlock(
                        id = id,
                        media = mediaOf(note.mediaRef, owner),
                        caption = note.caption.ifEmpty { null },
                        presentation = presentationValue(note.presentation),
                    ),
                )
            }

            is JournalNote.Video -> {
                EntryBlocksItem.VideoBlock(
                    EntryVideoBlock(
                        id = id,
                        media = mediaOf(note.mediaRef, owner),
                        caption = note.caption.ifEmpty { null },
                        durationMs = note.durationMs?.also { requireDuration(it, owner) },
                    ),
                )
            }

            is JournalNote.Audio -> {
                EntryBlocksItem.AudioBlock(
                    EntryAudioBlock(
                        id = id,
                        media = mediaOf(note.mediaRef, owner),
                        durationMs = note.durationMs.also { requireDuration(it, owner) },
                        caption = note.caption.ifEmpty { null },
                        transcript =
                            note.transcript?.let {
                                DefsRecordRef(
                                    Transcript.TYPE,
                                    NoteTranscriptMapper.transcriptRkeyOf(note.uid),
                                )
                            },
                    ),
                )
            }
        }
    }

    private fun noteOf(
        block: EntryBlocksItem,
        context: NoteContext,
        owner: String,
    ): JournalNote =
        when (block) {
            is EntryBlocksItem.HandwritingBlock -> {
                handwritingNoteOf(block.value, context, owner)
            }

            is EntryBlocksItem.TextBlock -> {
                textNoteOf(block.value, context, owner)
            }

            is EntryBlocksItem.ImageBlock -> {
                imageNoteOf(block.value, context, owner)
            }

            is EntryBlocksItem.VideoBlock -> {
                videoNoteOf(block.value, context, owner)
            }

            is EntryBlocksItem.AudioBlock -> {
                audioNoteOf(block.value, context, owner)
            }

            is EntryBlocksItem.Unknown -> {
                throw SpaceRecordMappingException("$owner: this version of LogDate can't show a ${block.type}")
            }
        }

    private fun handwritingNoteOf(
        block: EntryHandwritingBlock,
        context: NoteContext,
        owner: String,
    ): JournalNote.Handwriting {
        requireBlockId(block.id, context.uid, owner)
        val document =
            try {
                HandwritingDocument.decode(block.document)
            } catch (failure: IllegalArgumentException) {
                throw SpaceRecordMappingException("$owner: unreadable handwriting document", failure)
            }
        return JournalNote.Handwriting(
            uid = context.uid,
            creationTimestamp = context.created,
            lastUpdated = context.updated,
            document = document,
            location = context.location,
            timeZoneId = context.timeZoneId,
            capturedOn = context.capturedOn,
            capturedDuring = context.capturedDuring,
        )
    }

    private fun textNoteOf(
        block: EntryTextBlock,
        context: NoteContext,
        owner: String,
    ): JournalNote.Text {
        requireBlockId(block.id, context.uid, owner)
        return JournalNote.Text(
            uid = context.uid,
            creationTimestamp = context.created,
            lastUpdated = context.updated,
            content = markdownOf(block, owner),
            location = context.location,
            timeZoneId = context.timeZoneId,
            capturedOn = context.capturedOn,
            capturedDuring = context.capturedDuring,
        )
    }

    private fun imageNoteOf(
        block: EntryImageBlock,
        context: NoteContext,
        owner: String,
    ): JournalNote.Image {
        requireBlockId(block.id, context.uid, owner)
        return JournalNote.Image(
            uid = context.uid,
            creationTimestamp = context.created,
            lastUpdated = context.updated,
            mediaRef = localUriOf(block.media, context.uid),
            caption = block.caption.orEmpty(),
            presentation = photoPresentationOf(block.presentation),
            location = context.location,
            timeZoneId = context.timeZoneId,
            capturedOn = context.capturedOn,
            capturedDuring = context.capturedDuring,
        )
    }

    private fun videoNoteOf(
        block: EntryVideoBlock,
        context: NoteContext,
        owner: String,
    ): JournalNote.Video {
        requireBlockId(block.id, context.uid, owner)
        return JournalNote.Video(
            uid = context.uid,
            creationTimestamp = context.created,
            lastUpdated = context.updated,
            mediaRef = localUriOf(block.media, context.uid),
            caption = block.caption.orEmpty(),
            durationMs = block.durationMs,
            location = context.location,
            timeZoneId = context.timeZoneId,
            capturedOn = context.capturedOn,
            capturedDuring = context.capturedDuring,
        )
    }

    private fun audioNoteOf(
        block: EntryAudioBlock,
        context: NoteContext,
        owner: String,
    ): JournalNote.Audio {
        requireBlockId(block.id, context.uid, owner)
        block.transcript?.let { requireTranscriptRef(it, context.uid, owner) }
        return JournalNote.Audio(
            uid = context.uid,
            creationTimestamp = context.created,
            lastUpdated = context.updated,
            mediaRef = localUriOf(block.media, context.uid),
            durationMs = block.durationMs,
            caption = block.caption.orEmpty(),
            location = context.location,
            timeZoneId = context.timeZoneId,
            capturedOn = context.capturedOn,
            capturedDuring = context.capturedDuring,
        )
    }

    private fun mediaOf(
        mediaRef: String,
        owner: String,
    ): DefsMedia? = if (isUnavailable(mediaRef)) null else media.mediaFor(mediaRef, owner)

    private fun localUriOf(
        stored: DefsMedia?,
        uid: Uuid,
    ): String = stored?.let { media.downloaded(uid, it) } ?: UnavailableMediaReferences.archive(entityId = uid.toString())

    private fun locationOf(
        location: NoteLocation,
        owner: String,
    ): DefsLocation? {
        if (!location.hasLocation) return null
        val coordinates = location.coordinates
        return DefsLocation(
            geo =
                coordinates?.let {
                    Geo(
                        latitude = it.latitude.toDecimalString("$owner latitude"),
                        longitude = it.longitude.toDecimalString("$owner longitude"),
                        altitude = it.altitude?.toDecimalString("$owner altitude"),
                    )
                },
            accuracyCentimeters = coordinates?.accuracy?.toCentimeters("$owner accuracy"),
            place = location.place?.let { recordRef(Place.TYPE, it.id) },
            placeDetails = location.place?.let { placeDetailsOf(it, owner) },
        )
    }

    private fun placeDetailsOf(
        place: NotePlace,
        owner: String,
    ): DefsPlaceDetails? {
        val name = place.name
        val latitude = place.latitude
        val longitude = place.longitude
        if (name == null && latitude == null) return null
        if (name == null || latitude == null || longitude == null) {
            throw SpaceRecordMappingException(
                "$owner: its place ${place.id} has only some of a name and a center, which a record can't hold",
            )
        }
        return DefsPlaceDetails(
            name = name,
            geo =
                Geo(
                    latitude = latitude.toDecimalString("$owner place latitude"),
                    longitude = longitude.toDecimalString("$owner place longitude"),
                ),
        )
    }

    private fun noteLocationOf(
        location: DefsLocation,
        owner: String,
    ): NoteLocation? {
        val geo = location.geo
        val coordinates =
            geo?.let {
                NoteCoordinates(
                    latitude = parseDecimal("$owner latitude", it.latitude),
                    longitude = parseDecimal("$owner longitude", it.longitude),
                    altitude = it.altitude?.let { altitude -> parseDecimal("$owner altitude", altitude) },
                    accuracy = location.accuracyCentimeters?.centimetersToMeters(),
                )
            }
        if (location.place == null && location.placeDetails != null) {
            throw SpaceRecordMappingException("$owner: the location has place details but no place")
        }
        val place = location.place?.let { placeOf(it, location.placeDetails, owner) }
        if (coordinates == null && place == null) return null
        return NoteLocation(coordinates = coordinates, place = place)
    }

    private fun placeOf(
        ref: DefsRecordRef,
        details: DefsPlaceDetails?,
        owner: String,
    ): NotePlace {
        if (ref.collection != Place.TYPE) {
            throw SpaceRecordMappingException("$owner: the location's place is a ${ref.collection}, not a ${Place.TYPE}")
        }
        val placeId = parseUuidKey(Place.TYPE, ref.rkey)
        details ?: return NotePlace(id = placeId)
        return NotePlace(
            id = placeId,
            name = details.name,
            latitude = parseDecimal("$owner place latitude", details.geo.latitude),
            longitude = parseDecimal("$owner place longitude", details.geo.longitude),
        )
    }

    private fun requireBlockId(
        blockId: String,
        uid: Uuid,
        owner: String,
    ) {
        if (!sameRecordKey(blockId, uid.toString())) {
            throw SpaceRecordMappingException("$owner: its block $blockId isn't keyed by the entry's own id")
        }
    }

    private fun requireTranscriptRef(
        ref: DefsRecordRef,
        uid: Uuid,
        owner: String,
    ) {
        val expected = NoteTranscriptMapper.transcriptRkeyOf(uid)
        if (ref.collection != Transcript.TYPE || !sameRecordKey(ref.rkey, expected)) {
            throw SpaceRecordMappingException("$owner: its transcript is ${ref.collection}/${ref.rkey}, not ${Transcript.TYPE}/$expected")
        }
    }

    private fun markdownOf(
        block: EntryTextBlock,
        owner: String,
    ): String =
        when (block.format) {
            null, MARKDOWN -> {
                block.text
            }

            PLAIN -> {
                plainTextAsMarkdown(block.text)
                    ?: throw SpaceRecordMappingException("$owner: its plain text has a line that starts with whitespace")
            }

            else -> {
                throw SpaceRecordMappingException("$owner: this version of LogDate can't show ${block.format} text")
            }
        }

    private fun requireDuration(
        durationMs: Long,
        owner: String,
    ) {
        if (durationMs < 0) throw SpaceRecordMappingException("$owner: a duration of $durationMs ms is negative")
    }

    private fun isUnavailable(mediaRef: String): Boolean = mediaRef.isBlank() || UnavailableMediaReferences.isUnavailable(mediaRef)

    private data class NoteContext(
        val uid: Uuid,
        val created: Instant,
        val updated: Instant,
        val location: NoteLocation?,
        val timeZoneId: String?,
        val capturedOn: CaptureDevice?,
        val capturedDuring: CaptureActivity?,
    )

    private companion object {
        const val MARKDOWN = "markdown"
        const val PLAIN = "plain"

        fun presentationValue(presentation: PhotoPresentation): String =
            when (presentation) {
                PhotoPresentation.EdgeToEdge -> "edgeToEdge"
                PhotoPresentation.Framed -> "framed"
            }

        // The schema's default is edge to edge, and a framing a newer app adds falls back to it.
        fun photoPresentationOf(value: String?): PhotoPresentation =
            if (value == "framed") PhotoPresentation.Framed else PhotoPresentation.EdgeToEdge

        // The record uses the same names as the database for devices.
        fun capturedOnValue(device: CaptureDevice): String = device.wireName

        fun capturedDuringValue(activity: CaptureActivity): String =
            when (activity) {
                CaptureActivity.MOVING -> "moving"
                CaptureActivity.IN_VEHICLE -> "inVehicle"
            }

        // An activity a newer app records reads as unknown, as it does everywhere else in LogDate.
        fun captureActivityOf(value: String): CaptureActivity? =
            when (value) {
                "moving" -> CaptureActivity.MOVING
                "inVehicle" -> CaptureActivity.IN_VEHICLE
                else -> null
            }
    }
}
