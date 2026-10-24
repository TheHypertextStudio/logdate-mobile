package app.logdate.client.sync.space.mappers

import app.logdate.client.sync.space.SpaceMediaResolver
import app.logdate.client.sync.space.SpaceRecordMapper
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.blobCidFor
import app.logdate.client.sync.space.canonicalRecordKey
import app.logdate.client.sync.space.decodeRecord
import app.logdate.client.sync.space.encodeRecord
import app.logdate.client.sync.space.mediaFor
import app.logdate.client.sync.space.parseDatetime
import app.logdate.client.sync.space.parseDecimal
import app.logdate.client.sync.space.parseUuidKey
import app.logdate.client.sync.space.recordRef
import app.logdate.client.sync.space.toDatetime
import app.logdate.client.sync.space.toDecimalString
import app.logdate.lexicon.app.logdate.DefsLocation
import app.logdate.lexicon.app.logdate.DefsMedia
import app.logdate.lexicon.app.logdate.journal.Draft
import app.logdate.lexicon.app.logdate.journal.DraftAudioBlock
import app.logdate.lexicon.app.logdate.journal.DraftBlocksItem
import app.logdate.lexicon.app.logdate.journal.DraftCameraBlock
import app.logdate.lexicon.app.logdate.journal.DraftHandwritingBlock
import app.logdate.lexicon.app.logdate.journal.DraftImageBlock
import app.logdate.lexicon.app.logdate.journal.DraftTextBlock
import app.logdate.lexicon.app.logdate.journal.DraftVideoBlock
import app.logdate.lexicon.community.lexicon.location.Geo
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.HandwritingDocument
import app.logdate.shared.model.PhotoPresentation
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableHandwritingBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid
import app.logdate.lexicon.app.logdate.journal.Journal as JournalRecord

/**
 * An editor draft as the personal space stores it.
 *
 * @property draft The draft.
 * @property syncVersion The record's position in the space's change log, or 0 before it's written.
 *   An editor draft has no version of its own, so it travels next to the draft.
 */
data class SpaceDraft(
    val draft: EditorDraft,
    val syncVersion: Long = 0,
)

/**
 * Converts editor drafts to and from `app.logdate.journal.draft` records, keyed by the draft's id.
 *
 * Each block keeps its id, start time and captured coordinates, because each becomes its own entry
 * when the draft is saved. An empty caption, an empty journal selection and a block without a file
 * are written by leaving the field out, which the record reads the same way. A block's file must
 * already be uploaded to the personal space; until it is, the draft can't be written and the
 * failure is retryable.
 *
 * What a record can't hold, so the draft is refused instead of written without it:
 * - An altitude, or only one of a latitude and a longitude, because a record's coordinates need
 *   both a latitude and a longitude.
 * - A negative recording length.
 *
 * What this version can't read, so the record is refused instead of read without it:
 * - A kind of block this version doesn't know.
 * - A block id that isn't one of LogDate's ids.
 * - A block location that names a saved place or an accuracy, which a draft's block has no place for.
 * - A reference to a journal that isn't a journal record.
 *
 * A photo framing this version doesn't know reads as the record's default, edge to edge, as
 * entries do.
 *
 * @param media connects each block's files with their uploaded blobs.
 */
class DraftRecordMapper(
    private val media: SpaceMediaResolver = SpaceMediaResolver.Default,
) : SpaceRecordMapper<SpaceDraft> {
    override val collection: String = Draft.TYPE

    override fun rkeyOf(value: SpaceDraft): String = canonicalRecordKey(value.draft.id.toString())

    override fun toRecord(value: SpaceDraft): JsonObject {
        val draft = value.draft
        val owner = "$collection/${draft.id}"
        return encodeRecord(
            Draft.serializer(),
            Draft(
                blocks = draft.blocks.map { blockRecord(it, owner) },
                journals = draft.selectedJournalIds.map { recordRef(JournalRecord.TYPE, it) }.ifEmpty { null },
                createdAt = draft.createdAt.toDatetime("$owner createdAt"),
                updatedAt = draft.lastModifiedAt.toDatetime("$owner updatedAt"),
            ),
        )
    }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): SpaceDraft {
        val owner = "$collection/$rkey"
        val id = parseUuidKey(collection, rkey)
        val draft = decodeRecord(Draft.serializer(), collection, rkey, record)
        val created = parseDatetime("$owner createdAt", draft.createdAt)
        return SpaceDraft(
            EditorDraft(
                id = id,
                blocks = draft.blocks.map { block(it, owner) },
                selectedJournalIds = draft.journals.orEmpty().map { journalIdOf(it.collection, it.rkey, owner) },
                createdAt = created,
                lastModifiedAt = draft.updatedAt?.let { parseDatetime("$owner updatedAt", it) } ?: created,
            ),
        )
    }

    override fun blobCidsOf(value: SpaceDraft): List<String> {
        val owner = "$collection/${value.draft.id}"
        return value.draft.blocks
            .flatMap { block ->
                when (block) {
                    is SerializableHandwritingBlock, is SerializableTextBlock -> emptyList()
                    is SerializableImageBlock -> listOf(block.uri)
                    is SerializableVideoBlock -> listOf(block.uri, block.thumbnailUri)
                    is SerializableAudioBlock -> listOf(block.uri)
                    is SerializableCameraBlock -> listOf(block.uri)
                }
            }.filterNotNull()
            .map { media.blobCidFor(it, owner) }
    }

    private fun journalIdOf(
        collection: String,
        rkey: String,
        owner: String,
    ): Uuid {
        if (collection != JournalRecord.TYPE) throw SpaceRecordMappingException("$owner: it lists a $collection as a journal")
        return parseUuidKey(JournalRecord.TYPE, rkey)
    }

    private fun blockRecord(
        block: SerializableEntryBlock,
        draftOwner: String,
    ): DraftBlocksItem {
        val owner = "$draftOwner block ${block.id}"
        val id = canonicalRecordKey(block.id.toString())
        val createdAt = block.timestamp.toDatetime("$owner timestamp")
        val location = locationOf(block, owner)
        return when (block) {
            is SerializableHandwritingBlock -> {
                DraftBlocksItem.HandwritingBlock(
                    DraftHandwritingBlock(id = id, createdAt = createdAt, location = location, document = block.document.encode()),
                )
            }

            is SerializableTextBlock -> {
                DraftBlocksItem.TextBlock(DraftTextBlock(id = id, createdAt = createdAt, location = location, text = block.content))
            }

            is SerializableImageBlock -> {
                DraftBlocksItem.ImageBlock(
                    DraftImageBlock(
                        id = id,
                        createdAt = createdAt,
                        location = location,
                        media = mediaOf(block.uri, owner),
                        caption = block.caption.ifEmpty { null },
                        presentation = presentationValue(block.presentation),
                    ),
                )
            }

            is SerializableVideoBlock -> {
                DraftBlocksItem.VideoBlock(
                    DraftVideoBlock(
                        id = id,
                        createdAt = createdAt,
                        location = location,
                        media = mediaOf(block.uri, owner),
                        thumbnail = mediaOf(block.thumbnailUri, owner),
                        caption = block.caption.ifEmpty { null },
                    ),
                )
            }

            is SerializableAudioBlock -> {
                if (block.duration < 0) throw SpaceRecordMappingException("$owner: a recording length of ${block.duration} ms is negative")
                DraftBlocksItem.AudioBlock(
                    DraftAudioBlock(
                        id = id,
                        createdAt = createdAt,
                        location = location,
                        media = mediaOf(block.uri, owner),
                        durationMs = block.duration,
                        transcriptText = block.transcription,
                    ),
                )
            }

            is SerializableCameraBlock -> {
                DraftBlocksItem.CameraBlock(
                    DraftCameraBlock(id = id, createdAt = createdAt, location = location, media = mediaOf(block.uri, owner)),
                )
            }
        }
    }

    private fun block(
        item: DraftBlocksItem,
        draftOwner: String,
    ): SerializableEntryBlock =
        when (item) {
            is DraftBlocksItem.HandwritingBlock -> {
                handwritingBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.TextBlock -> {
                textBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.ImageBlock -> {
                imageBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.VideoBlock -> {
                videoBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.AudioBlock -> {
                audioBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.CameraBlock -> {
                cameraBlock(item.value, draftOwner)
            }

            is DraftBlocksItem.Unknown -> {
                throw SpaceRecordMappingException("$draftOwner: this version of LogDate can't show a ${item.type} block")
            }
        }

    private fun handwritingBlock(
        block: DraftHandwritingBlock,
        draftOwner: String,
    ): SerializableHandwritingBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        val document =
            try {
                HandwritingDocument.decode(block.document)
            } catch (failure: IllegalArgumentException) {
                throw SpaceRecordMappingException("$draftOwner: unreadable handwriting document", failure)
            }
        return SerializableHandwritingBlock(at.id, at.timestamp, at.latitude, at.longitude, at.altitude, document)
    }

    private fun textBlock(
        block: DraftTextBlock,
        draftOwner: String,
    ): SerializableTextBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        return SerializableTextBlock(at.id, at.timestamp, at.latitude, at.longitude, at.altitude, content = block.text)
    }

    private fun imageBlock(
        block: DraftImageBlock,
        draftOwner: String,
    ): SerializableImageBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        return SerializableImageBlock(
            id = at.id,
            timestamp = at.timestamp,
            locationLat = at.latitude,
            locationLng = at.longitude,
            altitude = at.altitude,
            uri = uriOf(block.media, at.id),
            caption = block.caption.orEmpty(),
            presentation = photoPresentationOf(block.presentation),
        )
    }

    private fun videoBlock(
        block: DraftVideoBlock,
        draftOwner: String,
    ): SerializableVideoBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        return SerializableVideoBlock(
            id = at.id,
            timestamp = at.timestamp,
            locationLat = at.latitude,
            locationLng = at.longitude,
            altitude = at.altitude,
            uri = uriOf(block.media, at.id),
            thumbnailUri = uriOf(block.thumbnail, at.id),
            caption = block.caption.orEmpty(),
        )
    }

    private fun audioBlock(
        block: DraftAudioBlock,
        draftOwner: String,
    ): SerializableAudioBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        return SerializableAudioBlock(
            id = at.id,
            timestamp = at.timestamp,
            locationLat = at.latitude,
            locationLng = at.longitude,
            altitude = at.altitude,
            uri = uriOf(block.media, at.id),
            duration = block.durationMs,
            transcription = block.transcriptText,
        )
    }

    private fun cameraBlock(
        block: DraftCameraBlock,
        draftOwner: String,
    ): SerializableCameraBlock {
        val at = capture(block.id, block.createdAt, block.location, draftOwner)
        return SerializableCameraBlock(
            id = at.id,
            timestamp = at.timestamp,
            locationLat = at.latitude,
            locationLng = at.longitude,
            altitude = at.altitude,
            uri = uriOf(block.media, at.id),
        )
    }

    private fun mediaOf(
        uri: String?,
        owner: String,
    ): DefsMedia? = uri?.let { media.mediaFor(it, owner) }

    private fun uriOf(
        stored: DefsMedia?,
        blockId: Uuid,
    ): String? = stored?.let { media.downloaded(blockId, it) }

    private fun locationOf(
        block: SerializableEntryBlock,
        owner: String,
    ): DefsLocation? {
        val latitude = block.locationLat
        val longitude = block.locationLng
        val altitude = block.altitude
        if (latitude == null && longitude == null && altitude == null) return null
        if (latitude == null || longitude == null) {
            throw SpaceRecordMappingException("$owner: its location has an altitude or only one coordinate, which a record can't hold")
        }
        return DefsLocation(
            geo =
                Geo(
                    latitude = latitude.toDecimalString("$owner latitude"),
                    longitude = longitude.toDecimalString("$owner longitude"),
                    altitude = altitude?.toDecimalString("$owner altitude"),
                ),
        )
    }

    private fun capture(
        id: String,
        createdAt: String,
        location: DefsLocation?,
        draftOwner: String,
    ): Capture {
        val blockId =
            Uuid.parseOrNull(id) ?: throw SpaceRecordMappingException("$draftOwner: it has a block whose id isn't one of LogDate's ids")
        val owner = "$draftOwner block $blockId"
        if (location != null && (location.place != null || location.placeDetails != null || location.accuracyCentimeters != null)) {
            throw SpaceRecordMappingException("$owner: its location has a place or an accuracy, which a draft block can't hold")
        }
        val geo = location?.let { it.geo ?: throw SpaceRecordMappingException("$owner: its location has no coordinates") }
        return Capture(
            id = blockId,
            timestamp = parseDatetime("$owner createdAt", createdAt),
            latitude = geo?.let { parseDecimal("$owner latitude", it.latitude) },
            longitude = geo?.let { parseDecimal("$owner longitude", it.longitude) },
            altitude = geo?.altitude?.let { parseDecimal("$owner altitude", it) },
        )
    }

    /** The fields every kind of block has, in the form the editor stores them. */
    private class Capture(
        val id: Uuid,
        val timestamp: Instant,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
    )

    private companion object {
        const val EDGE_TO_EDGE = "edgeToEdge"
        const val FRAMED = "framed"

        fun presentationValue(presentation: PhotoPresentation): String =
            when (presentation) {
                PhotoPresentation.EdgeToEdge -> EDGE_TO_EDGE
                PhotoPresentation.Framed -> FRAMED
            }

        // The schema's default is edge to edge, and a framing a newer app adds falls back to it.
        fun photoPresentationOf(value: String?): PhotoPresentation =
            if (value ==
                FRAMED
            ) {
                PhotoPresentation.Framed
            } else {
                PhotoPresentation.EdgeToEdge
            }
    }
}
