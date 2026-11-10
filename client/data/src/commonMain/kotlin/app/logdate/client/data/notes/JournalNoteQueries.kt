package app.logdate.client.data.notes

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.GenericNoteData
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.MediaCaptionEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.VideoNoteEntity
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCounts
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.transcription.TranscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Read-side queries and reactive streams over the note, caption, place, and transcript tables.
 */
internal class JournalNoteQueries(
    private val textNoteDao: TextNoteDao,
    private val imageNoteDao: ImageNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val videoNoteDao: VideoNoteDao,
    private val journalContentDao: JournalContentDao,
    private val mediaCaptionDao: MediaCaptionDao,
    private val notePlaceResolver: NotePlaceResolver,
    private val transcriptionRepository: TranscriptionRepository?,
) {
    val allNotesObserved: Flow<List<JournalNote>> =
        notePlaceResolver
            .observeAll()
            .combine(
                observeNoteBuckets(
                    textFlow = textNoteDao.getAllNotes(),
                    imageFlow = imageNoteDao.getAllNotes(),
                    audioFlow = audioNoteDao.getAllNotes(),
                    videoFlow = videoNoteDao.getAllNotes(),
                ),
            ) { placeLookup, buckets ->
                buckets.toNotes(placeLookup)
            }.combine(mediaCaptionDao.observeAll()) { notes, captions ->
                val captionMap = captions.associate { it.noteId to it.caption }
                notes.map { note -> note.withCaption(captionMap) }
            }.withTranscripts(transcriptionRepository)

    /** Reads only the notes linked to [journalId]; a journal too large for one query filters the full list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> =
        journalContentDao
            .getContentForJournal(journalId)
            .distinctUntilChanged()
            .flatMapLatest { contentIds ->
                if (contentIds.size > QUERY_BATCH_SIZE) {
                    val linked = contentIds.toSet()
                    allNotesObserved.map { notes -> notes.filter { it.uid in linked } }
                } else {
                    observeScopedNotes(
                        observeNoteBuckets(
                            textFlow = textNoteDao.observeNotesByIds(contentIds),
                            imageFlow = imageNoteDao.observeNotesByIds(contentIds),
                            audioFlow = audioNoteDao.observeNotesByIds(contentIds),
                            videoFlow = videoNoteDao.observeNotesByIds(contentIds),
                        ),
                    )
                }
            }

    suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> =
        journalContentDao
            .getAllLinks()
            .map { link -> link.journalId to link.contentId }

    fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> {
        val startMillis = start.toEpochMilliseconds()
        val endMillis = end.toEpochMilliseconds()

        return notePlaceResolver
            .observeAll()
            .combine(
                observeNoteBuckets(
                    textFlow = textNoteDao.getNotesInRange(startMillis, endMillis),
                    imageFlow = imageNoteDao.getNotesInRange(startMillis, endMillis),
                    audioFlow = audioNoteDao.getNotesInRange(startMillis, endMillis),
                    videoFlow = videoNoteDao.getNotesInRange(startMillis, endMillis),
                ),
            ) { placeLookup, buckets ->
                buckets.toNotes(placeLookup)
            }.combine(mediaCaptionDao.observeAll()) { notes, captions ->
                val captionMap = captions.associate { it.noteId to it.caption }
                notes.map { note -> note.withCaption(captionMap) }
            }.withTranscripts(transcriptionRepository)
    }

    /**
     * A page across the four note tables. The newest `offset + pageSize` rows of each table always
     * contain the requested page of their union, so each table reads at most that many rows.
     */
    fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> {
        require(pageSize >= 0 && offset >= 0) { "A page needs a non-negative size and offset" }
        val window = offset + pageSize
        return observeScopedNotes(
            observeNoteBuckets(
                textFlow = textNoteDao.getRecentNotes(window),
                imageFlow = imageNoteDao.getRecentNotes(window),
                audioFlow = audioNoteDao.getRecentNotes(window),
                videoFlow = videoNoteDao.getRecentNotes(window),
            ),
        ).map { notes ->
            notes
                .sortedByDescending { it.creationTimestamp }
                .drop(offset)
                .take(pageSize)
        }
    }

    fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = observeNotesPage(pageSize, offset = 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeNoteById(noteId: Uuid): Flow<JournalNote?> {
        val stored =
            combine(
                textNoteDao.observeNoteById(noteId),
                imageNoteDao.observeNoteById(noteId),
                audioNoteDao.observeNoteById(noteId),
                videoNoteDao.observeNoteById(noteId),
                mediaCaptionDao.observeCaption(noteId),
            ) { text, image, audio, video, caption ->
                StoredNote(text ?: image ?: audio ?: video, caption?.caption.orEmpty())
            }.distinctUntilChanged()
                .mapLatest { it.toNote() }
        val transcript = transcriptionRepository?.observeTranscription(noteId) ?: return stored.distinctUntilChanged()
        return stored
            .combine(transcript) { note, transcription ->
                if (note is JournalNote.Audio) note.copy(transcript = transcription?.transcriptDocument ?: note.transcript) else note
            }.distinctUntilChanged()
    }

    private data class StoredNote(
        val entity: GenericNoteData?,
        val caption: String,
    )

    private suspend fun StoredNote.toNote(): JournalNote? {
        val place = entity?.referencedPlaceId()?.let { notePlaceResolver.get(it) }
        return when (entity) {
            null -> null
            is TextNoteEntity -> entity.toModel(place)
            is ImageNoteEntity -> entity.toModel(place, caption)
            is AudioNoteEntity -> entity.toModel(place, caption)
            is VideoNoteEntity -> entity.toModel(place, caption)
            else -> error("Unknown note table for ${entity.uid}")
        }
    }

    /** Joins places and captions for exactly the notes in [buckets], instead of every caption stored. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeScopedNotes(buckets: Flow<NoteBuckets>): Flow<List<JournalNote>> =
        notePlaceResolver
            .observeAll()
            .combine(buckets) { placeLookup, current -> current to current.toNotes(placeLookup) }
            .flatMapLatest { (current, notes) ->
                val captioned = current.mediaIds()
                if (captioned.isEmpty() || captioned.size > QUERY_BATCH_SIZE) {
                    mediaCaptionDao.observeAll().map { captions -> notes.withCaptions(captions) }
                } else {
                    mediaCaptionDao.observeCaptions(captioned).map { captions -> notes.withCaptions(captions) }
                }
            }.withTranscripts(transcriptionRepository)

    private fun List<JournalNote>.withCaptions(captions: List<MediaCaptionEntity>): List<JournalNote> {
        val captionMap = captions.associate { it.noteId to it.caption }
        return map { note -> note.withCaption(captionMap) }
    }

    fun observeEntryTimestamps(): Flow<List<Instant>> =
        combine(
            textNoteDao.observeCreatedTimestamps(),
            imageNoteDao.observeCreatedTimestamps(),
            audioNoteDao.observeCreatedTimestamps(),
            videoNoteDao.observeCreatedTimestamps(),
        ) { text, image, audio, video ->
            (text + image + audio + video).map(Instant::fromEpochMilliseconds)
        }

    fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> =
        notePlaceResolver
            .observeAll()
            .onStart { emit(emptyMap()) }
            .combine(
                observeNoteBuckets(
                    textFlow = textNoteDao.getRecentNotes(limit),
                    imageFlow = imageNoteDao.getRecentNotes(limit),
                    audioFlow = audioNoteDao.getRecentNotes(limit),
                    videoFlow = videoNoteDao.getRecentNotes(limit),
                ),
            ) { placeLookup, buckets ->
                buckets
                    .toNotes(placeLookup)
                    .sortedByDescending { it.creationTimestamp }
                    .take(limit)
            }.combine(mediaCaptionDao.observeAll().onStart { emit(emptyList()) }) { notes, captions ->
                val captionMap = captions.associate { it.noteId to it.caption }
                notes.map { note -> note.withCaption(captionMap) }
            }.withTranscripts(transcriptionRepository, waitForInitialTranscripts = false)

    fun observeRecentAudioNotes(limit: Int): Flow<List<JournalNote.Audio>> =
        notePlaceResolver
            .observeAll()
            .combine(audioNoteDao.getRecentNotes(limit)) { placeLookup, entities ->
                entities.map { it.toModel(it.referencedPlaceId()?.let(placeLookup::get)) }
            }.combine(mediaCaptionDao.observeAll()) { notes, captions ->
                val captionMap = captions.associate { it.noteId to it.caption }
                notes.map { note -> note.copy(caption = captionMap[note.uid] ?: note.caption) }
            }.withTranscripts(transcriptionRepository)
            .map { it.filterIsInstance<JournalNote.Audio>() }

    fun observeNotesForDay(day: LocalDate): Flow<List<JournalNote>> {
        val timezone = TimeZone.currentSystemDefault()
        val start = day.atStartOfDayIn(timezone).toEpochMilliseconds()
        val endExclusive = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timezone).toEpochMilliseconds()

        return notePlaceResolver
            .observeAll()
            .combine(
                observeNoteBuckets(
                    textFlow = textNoteDao.getNotesInRange(start, endExclusive),
                    imageFlow = imageNoteDao.getNotesInRange(start, endExclusive),
                    audioFlow = audioNoteDao.getNotesInRange(start, endExclusive),
                    videoFlow = videoNoteDao.getNotesInRange(start, endExclusive),
                ),
            ) { placeLookup, buckets ->
                buckets
                    .toNotes(placeLookup)
                    .filter { note ->
                        note.creationTimestamp
                            .toLocalDateTime(timezone)
                            .date == day
                    }.sortedByDescending(JournalNote::creationTimestamp)
            }.combine(mediaCaptionDao.observeAll()) { notes, captions ->
                val captionMap = captions.associate { it.noteId to it.caption }
                notes.map { note -> note.withCaption(captionMap) }
            }.withTranscripts(transcriptionRepository)
    }

    suspend fun getNotesBefore(
        beforeExclusive: Instant,
        limit: Int,
    ): List<JournalNote> =
        NoteBuckets(
            textNotes = textNoteDao.getRecentNotesBefore(beforeExclusive.toEpochMilliseconds(), limit),
            imageNotes = imageNoteDao.getRecentNotesBefore(beforeExclusive.toEpochMilliseconds(), limit),
            audioNotes = audioNoteDao.getRecentNotesBefore(beforeExclusive.toEpochMilliseconds(), limit),
            videoNotes = videoNoteDao.getRecentNotesBefore(beforeExclusive.toEpochMilliseconds(), limit),
        ).toNotes(notePlaceResolver.observeAll().first())
            .sortedByDescending(JournalNote::creationTimestamp)
            .take(limit)
            .map { note ->
                note
                    .withCaption(mapOf(note.uid to (mediaCaptionDao.getCaption(note.uid)?.caption ?: "")))
                    .withTranscript(transcriptionRepository)
            }

    suspend fun hasNotesBefore(beforeExclusive: Instant): Boolean {
        val beforeTimestamp = beforeExclusive.toEpochMilliseconds()
        return textNoteDao.hasNotesBefore(beforeTimestamp) ||
            imageNoteDao.hasNotesBefore(beforeTimestamp) ||
            audioNoteDao.hasNotesBefore(beforeTimestamp) ||
            videoNoteDao.hasNotesBefore(beforeTimestamp)
    }

    suspend fun notesReferencingMediaPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val candidates = paths.toList()
        return buildSet {
            addAll(audioNoteDao.findReferencedContentUris(candidates))
            addAll(imageNoteDao.findReferencedContentUris(candidates))
            addAll(videoNoteDao.findReferencedContentUris(candidates))
        }
    }

    suspend fun getNoteById(noteId: Uuid): JournalNote? {
        // Try each note type DAO until we find the note
        run {
            textNoteDao.findNoteOneOff(noteId)?.let { note ->
                note.toModel(note.referencedPlaceId()?.let { placeId -> notePlaceResolver.get(placeId) })
            }
        }?.let { return it }
        run {
            val caption = mediaCaptionDao.getCaption(noteId)?.caption ?: ""
            imageNoteDao.findNoteOneOff(noteId)?.let { note ->
                note.toModel(note.referencedPlaceId()?.let { placeId -> notePlaceResolver.get(placeId) }, caption)
            }
        }?.let { return it }
        run {
            val caption = mediaCaptionDao.getCaption(noteId)?.caption ?: ""
            audioNoteDao.findNoteOneOff(noteId)?.let { note ->
                note
                    .toModel(
                        note.referencedPlaceId()?.let { placeId ->
                            notePlaceResolver.get(placeId)
                        },
                        caption,
                    ).withTranscript(transcriptionRepository)
            }
        }?.let { return it }
        run {
            val caption = mediaCaptionDao.getCaption(noteId)?.caption ?: ""
            videoNoteDao.findNoteOneOff(noteId)?.let { note ->
                note.toModel(note.referencedPlaceId()?.let { placeId -> notePlaceResolver.get(placeId) }, caption)
            }
        }?.let { return it }
        return null
    }

    suspend fun countNotes(): NoteCounts {
        val media = imageNoteDao.countNotes() + audioNoteDao.countNotes() + videoNoteDao.countNotes()
        return NoteCounts(total = textNoteDao.countNotes() + media, withMedia = media)
    }

    suspend fun getNotesByIds(noteIds: Set<Uuid>): Map<Uuid, JournalNote> =
        noteIds
            .chunked(QUERY_BATCH_SIZE)
            .flatMap { ids ->
                val buckets =
                    NoteBuckets(
                        textNotes = textNoteDao.findNotesByIds(ids),
                        imageNotes = imageNoteDao.findNotesByIds(ids),
                        audioNotes = audioNoteDao.findNotesByIds(ids),
                        videoNotes = videoNoteDao.findNotesByIds(ids),
                    )
                val places =
                    buckets
                        .placeIds()
                        .mapNotNull { placeId -> notePlaceResolver.get(placeId)?.let { placeId to it } }
                        .toMap()
                val captions = mediaCaptionDao.getCaptions(ids).associate { it.noteId to it.caption }
                buckets.toNotes(places).map { note -> note.withCaption(captions).withTranscript(transcriptionRepository) }
            }.associateBy(JournalNote::uid)

    private fun observeNoteBuckets(
        textFlow: Flow<List<TextNoteEntity>>,
        imageFlow: Flow<List<ImageNoteEntity>>,
        audioFlow: Flow<List<AudioNoteEntity>>,
        videoFlow: Flow<List<VideoNoteEntity>>,
    ): Flow<NoteBuckets> =
        textFlow
            .combine(imageFlow) { textNotes, imageNotes ->
                textNotes to imageNotes
            }.combine(audioFlow) { textAndImageNotes, audioNotes ->
                Triple(textAndImageNotes.first, textAndImageNotes.second, audioNotes)
            }.combine(videoFlow) { textImageAndAudioNotes, videoNotes ->
                NoteBuckets(
                    textNotes = textImageAndAudioNotes.first,
                    imageNotes = textImageAndAudioNotes.second,
                    audioNotes = textImageAndAudioNotes.third,
                    videoNotes = videoNotes,
                )
            }

    private data class NoteBuckets(
        val textNotes: List<TextNoteEntity>,
        val imageNotes: List<ImageNoteEntity>,
        val audioNotes: List<AudioNoteEntity>,
        val videoNotes: List<VideoNoteEntity>,
    ) {
        fun mediaIds(): List<Uuid> = imageNotes.map { it.uid } + audioNotes.map { it.uid } + videoNotes.map { it.uid }

        fun placeIds(): Set<Uuid> =
            buildSet {
                textNotes.mapNotNullTo(this) { it.referencedPlaceId() }
                imageNotes.mapNotNullTo(this) { it.referencedPlaceId() }
                audioNotes.mapNotNullTo(this) { it.referencedPlaceId() }
                videoNotes.mapNotNullTo(this) { it.referencedPlaceId() }
            }

        fun toNotes(placeLookup: Map<Uuid, NotePlace>): List<JournalNote> =
            textNotes.map { it.toModel(it.referencedPlaceId()?.let(placeLookup::get)) } +
                imageNotes.map { it.toModel(it.referencedPlaceId()?.let(placeLookup::get)) } +
                audioNotes.map { it.toModel(it.referencedPlaceId()?.let(placeLookup::get)) } +
                videoNotes.map { it.toModel(it.referencedPlaceId()?.let(placeLookup::get)) }
    }
}

/** Stays well under SQLite's bound-parameter limit for `IN (...)` lookups. */
private const val QUERY_BATCH_SIZE = 500
