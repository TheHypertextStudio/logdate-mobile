package app.logdate.client.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.logdate.client.database.converters.DurationConverter
import app.logdate.client.database.converters.LocationDataConverter
import app.logdate.client.database.converters.MediaDimensionsConverter
import app.logdate.client.database.converters.StringListConverter
import app.logdate.client.database.converters.TimestampConverter
import app.logdate.client.database.converters.UuidConverter
import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.AudioTagDao
import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.EventNoteLinkDao
import app.logdate.client.database.dao.HealthSnapshotDao
import app.logdate.client.database.dao.HistoryOwnerAdoptionDao
import app.logdate.client.database.dao.HistoryRecordDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.JournalDao
import app.logdate.client.database.dao.JournalNotesDao
import app.logdate.client.database.dao.LocationActivityDao
import app.logdate.client.database.dao.LocationHistoryDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.PlaceDao
import app.logdate.client.database.dao.PostcardDao
import app.logdate.client.database.dao.SearchDao
import app.logdate.client.database.dao.StickerDao
import app.logdate.client.database.dao.StorageMetadataDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.dao.UserDevicesDao
import app.logdate.client.database.dao.UserMediaDao
import app.logdate.client.database.dao.UserPlaceDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.dao.journals.JournalMergeDao
import app.logdate.client.database.dao.maintenance.IntegrityDao
import app.logdate.client.database.dao.media.IndexedMediaDao
import app.logdate.client.database.dao.media.MediaExifDao
import app.logdate.client.database.dao.moments.MarkedMomentDao
import app.logdate.client.database.dao.people.InferredPersonClusterDao
import app.logdate.client.database.dao.people.InferredPersonEvidenceDao
import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.database.dao.people.PersonLinkDao
import app.logdate.client.database.dao.people.PersonResolutionDecisionDao
import app.logdate.client.database.dao.rewind.CachedRewindDao
import app.logdate.client.database.dao.rewind.ReflectionPromptResponseDao
import app.logdate.client.database.dao.rewind.RewindGenerationRequestDao
import app.logdate.client.database.dao.sync.DownloadInboxDao
import app.logdate.client.database.dao.sync.PeerDeliveryDao
import app.logdate.client.database.dao.sync.PendingUploadRetryDao
import app.logdate.client.database.dao.sync.SpaceRecordStateDao
import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.AudioTagEntity
import app.logdate.client.database.entities.EventEntity
import app.logdate.client.database.entities.EventNoteLinkEntity
import app.logdate.client.database.entities.HealthSnapshotEntity
import app.logdate.client.database.entities.HistoryCursorEntity
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.JournalNoteCrossRef
import app.logdate.client.database.entities.LocationActivityEntity
import app.logdate.client.database.entities.LocationLogEntity
import app.logdate.client.database.entities.MediaCaptionEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.PostcardEntity
import app.logdate.client.database.entities.SearchIndexMetadataEntity
import app.logdate.client.database.entities.StickerEntity
import app.logdate.client.database.entities.StorageMetadataEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionSegmentEntity
import app.logdate.client.database.entities.UserDeviceEntity
import app.logdate.client.database.entities.UserPlaceEntity
import app.logdate.client.database.entities.VideoNoteEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.database.entities.journals.JournalMergeEntity
import app.logdate.client.database.entities.media.IndexedImageEntity
import app.logdate.client.database.entities.media.IndexedVideoEntity
import app.logdate.client.database.entities.media.MediaExifMetadataEntity
import app.logdate.client.database.entities.media.MediaImageEntity
import app.logdate.client.database.entities.moments.MarkedMomentEntity
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.database.entities.people.PersonResolutionDecisionEntity
import app.logdate.client.database.entities.rewind.ReflectionPromptResponseEntity
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindGenerationRequestEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import app.logdate.client.database.entities.rewind.RewindTextContentEntity
import app.logdate.client.database.entities.rewind.RewindVideoContentEntity
import app.logdate.client.database.entities.sync.DownloadCheckpointEntity
import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.entities.sync.SpaceRecordStateEntity
import app.logdate.client.database.entities.sync.SyncCursorEntity
import app.logdate.client.database.migrations.MIGRATION_10_11
import app.logdate.client.database.migrations.MIGRATION_11_12
import app.logdate.client.database.migrations.MIGRATION_12_13
import app.logdate.client.database.migrations.MIGRATION_13_14
import app.logdate.client.database.migrations.MIGRATION_14_15
import app.logdate.client.database.migrations.MIGRATION_15_16
import app.logdate.client.database.migrations.MIGRATION_16_17
import app.logdate.client.database.migrations.MIGRATION_17_18
import app.logdate.client.database.migrations.MIGRATION_18_19
import app.logdate.client.database.migrations.MIGRATION_19_20
import app.logdate.client.database.migrations.MIGRATION_1_2
import app.logdate.client.database.migrations.MIGRATION_20_21
import app.logdate.client.database.migrations.MIGRATION_21_22
import app.logdate.client.database.migrations.MIGRATION_22_23
import app.logdate.client.database.migrations.MIGRATION_23_24
import app.logdate.client.database.migrations.MIGRATION_24_25
import app.logdate.client.database.migrations.MIGRATION_25_26
import app.logdate.client.database.migrations.MIGRATION_26_27
import app.logdate.client.database.migrations.MIGRATION_27_28
import app.logdate.client.database.migrations.MIGRATION_28_29
import app.logdate.client.database.migrations.MIGRATION_29_30
import app.logdate.client.database.migrations.MIGRATION_2_3
import app.logdate.client.database.migrations.MIGRATION_30_31
import app.logdate.client.database.migrations.MIGRATION_31_32
import app.logdate.client.database.migrations.MIGRATION_32_33
import app.logdate.client.database.migrations.MIGRATION_33_34
import app.logdate.client.database.migrations.MIGRATION_34_35
import app.logdate.client.database.migrations.MIGRATION_35_36
import app.logdate.client.database.migrations.MIGRATION_36_37
import app.logdate.client.database.migrations.MIGRATION_37_38
import app.logdate.client.database.migrations.MIGRATION_38_39
import app.logdate.client.database.migrations.MIGRATION_39_40
import app.logdate.client.database.migrations.MIGRATION_3_4
import app.logdate.client.database.migrations.MIGRATION_40_41
import app.logdate.client.database.migrations.MIGRATION_41_42
import app.logdate.client.database.migrations.MIGRATION_42_43
import app.logdate.client.database.migrations.MIGRATION_43_44
import app.logdate.client.database.migrations.MIGRATION_44_45
import app.logdate.client.database.migrations.MIGRATION_45_46
import app.logdate.client.database.migrations.MIGRATION_46_47
import app.logdate.client.database.migrations.MIGRATION_47_48
import app.logdate.client.database.migrations.MIGRATION_48_49
import app.logdate.client.database.migrations.MIGRATION_49_50
import app.logdate.client.database.migrations.MIGRATION_4_5
import app.logdate.client.database.migrations.MIGRATION_50_51
import app.logdate.client.database.migrations.MIGRATION_51_52
import app.logdate.client.database.migrations.MIGRATION_52_53
import app.logdate.client.database.migrations.MIGRATION_53_54
import app.logdate.client.database.migrations.MIGRATION_54_55
import app.logdate.client.database.migrations.MIGRATION_55_56
import app.logdate.client.database.migrations.MIGRATION_56_57
import app.logdate.client.database.migrations.MIGRATION_57_58
import app.logdate.client.database.migrations.MIGRATION_58_59
import app.logdate.client.database.migrations.MIGRATION_59_60
import app.logdate.client.database.migrations.MIGRATION_5_6
import app.logdate.client.database.migrations.MIGRATION_60_61
import app.logdate.client.database.migrations.MIGRATION_6_7
import app.logdate.client.database.migrations.MIGRATION_7_8
import app.logdate.client.database.migrations.MIGRATION_8_9
import app.logdate.client.database.migrations.MIGRATION_9_10
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The main on-device database for a LogDate client.
 *
 * This database is responsible for storing all data that is not ephemeral, such as projects and
 * experiments. It is built on top of Room, a SQLite database library, and is designed to be used
 * with Kotlin Multiplatform on JVM, Android, and native iOS build targets.
 *
 * @see [getRoomDatabase]
 */
@Database(
    entities = [
        TextNoteEntity::class,
        ImageNoteEntity::class,
        VideoNoteEntity::class,
        AudioNoteEntity::class,
        JournalEntity::class,
        JournalNoteCrossRef::class,
        LocationLogEntity::class,
        HistoryRecordEntity::class,
        HistoryCursorEntity::class,
        LocationActivityEntity::class,
        StorageMetadataEntity::class,
        UserDeviceEntity::class,
        MediaImageEntity::class,
        JournalContentEntityLink::class,
        JournalMergeEntity::class,
        // Rewind entities
        RewindEntity::class,
        RewindTextContentEntity::class,
        RewindImageContentEntity::class,
        RewindVideoContentEntity::class,
        RewindGenerationRequestEntity::class,
        // Indexed media entities
        IndexedImageEntity::class,
        IndexedVideoEntity::class,
        // Sync metadata entities
        SyncCursorEntity::class,
        PendingUploadEntity::class,
        DownloadInboxEntity::class,
        DownloadCheckpointEntity::class,
        PeerDeliveryEntity::class,
        SpaceRecordStateEntity::class,
        // Others
        TranscriptionEntity::class,
        TranscriptionSegmentEntity::class,
        PlaceEntity::class,
        UserPlaceEntity::class,
        MediaCaptionEntity::class,
        MediaExifMetadataEntity::class,
        HealthSnapshotEntity::class,
        // Postcards
        PostcardEntity::class,
        StickerEntity::class,
        SearchIndexMetadataEntity::class,
        // Events
        EventEntity::class,
        EventNoteLinkEntity::class,
        // Ambient sound tags on audio notes
        AudioTagEntity::class,
        // Typed replies to rewind noticing prompts
        ReflectionPromptResponseEntity::class,
        // People
        PersonEntity::class,
        InferredPersonClusterEntity::class,
        InferredPersonEvidenceEntity::class,
        PersonLinkEntity::class,
        PersonResolutionDecisionEntity::class,
        // Moments marked on the watch
        MarkedMomentEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(
    TimestampConverter::class,
    UuidConverter::class,
    DurationConverter::class,
    MediaDimensionsConverter::class,
    LocationDataConverter::class,
    StringListConverter::class,
)
@ConstructedBy(LogDateDatabaseConstructor::class)
abstract class LogDateDatabase : RoomDatabase() {
    abstract fun textNoteDao(): TextNoteDao

    abstract fun imageNoteDao(): ImageNoteDao

    abstract fun videoNoteDao(): VideoNoteDao

    abstract fun audioNoteDao(): AudioNoteDao

    abstract fun journalDao(): JournalDao

    abstract fun journalNotesDao(): JournalNotesDao

    abstract fun journalMergeDao(): JournalMergeDao

    abstract fun journalContentDao(): JournalContentDao

    abstract fun rewindDao(): CachedRewindDao

    abstract fun rewindGenerationRequestDao(): RewindGenerationRequestDao

    abstract fun locationActivityDao(): LocationActivityDao

    abstract fun historyRecordDao(): HistoryRecordDao

    abstract fun historyOwnerAdoptionDao(): HistoryOwnerAdoptionDao

    abstract fun locationHistoryDao(): LocationHistoryDao

    abstract fun storageMetadataDao(): StorageMetadataDao

    abstract fun userDevicesDao(): UserDevicesDao

    abstract fun userMediaDao(): UserMediaDao

    abstract fun indexedMediaDao(): IndexedMediaDao

    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun searchDao(): SearchDao

    abstract fun downloadInboxDao(): DownloadInboxDao

    abstract fun pendingUploadRetryDao(): PendingUploadRetryDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun peerDeliveryDao(): PeerDeliveryDao

    abstract fun spaceRecordStateDao(): SpaceRecordStateDao

    abstract fun markedMomentDao(): MarkedMomentDao

    abstract fun integrityDao(): IntegrityDao

    abstract fun placeDao(): PlaceDao

    abstract fun userPlaceDao(): UserPlaceDao

    abstract fun mediaCaptionDao(): MediaCaptionDao

    abstract fun mediaExifDao(): MediaExifDao

    abstract fun healthSnapshotDao(): HealthSnapshotDao

    abstract fun postcardDao(): PostcardDao

    abstract fun stickerDao(): StickerDao

    abstract fun eventDao(): EventDao

    abstract fun eventNoteLinkDao(): EventNoteLinkDao

    abstract fun audioTagDao(): AudioTagDao

    abstract fun reflectionPromptResponseDao(): ReflectionPromptResponseDao

    abstract fun personDao(): PersonDao

    abstract fun inferredPersonClusterDao(): InferredPersonClusterDao

    abstract fun inferredPersonEvidenceDao(): InferredPersonEvidenceDao

    abstract fun personLinkDao(): PersonLinkDao

    abstract fun personResolutionDecisionDao(): PersonResolutionDecisionDao
}

/**
 * The name of the [LogDateDatabase] file.
 */
internal const val DATABASE_NAME = "logdate"
internal const val DATABASE_VERSION = 61

internal expect val roomDatabaseDispatcher: CoroutineDispatcher

/**
 * Creates a new [LogDateDatabase].
 *
 * This is the main function to use when a client needs a [LogDateDatabase] and must only be
 * called once per application lifecycle. This depends on a [RoomDatabase.Builder] to be passed in,
 * which can be found in the platform-specific source set. This builder is responsible for
 * applying all necessary configurations to the database, including database migrations.
 *
 * This creates a SQLite database that uses the bundled SQLite driver and runs all queries on the
 * platform database dispatcher.
 *
 * @param builder The [RoomDatabase.Builder] to use to create the database.
 * @param driver The SQLite driver to use for the database. Defaults to [BundledSQLiteDriver].
 * @param dispatcher The dispatcher to use for all database queries. Defaults to [roomDatabaseDispatcher].
 * Missing upgrade or downgrade migrations refuse to open the retained database. Persistent
 * journals never enable Room's destructive fallback; its boolean parameter only controls which
 * tables are deleted. Disposable tests may configure their own builder explicitly.
 *
 * @return A new [LogDateDatabase] instance.
 */
fun getRoomDatabase(
    builder: RoomDatabase.Builder<LogDateDatabase>,
    driver: SQLiteDriver? = BundledSQLiteDriver(),
    dispatcher: CoroutineDispatcher = roomDatabaseDispatcher,
): LogDateDatabase {
    val configured =
        builder
            .addMigrations(*DATABASE_MIGRATIONS)
            .addCallback(FtsTableCallback)
            .setQueryCoroutineContext(dispatcher)

    driver?.let { configured.setDriver(it) }

    return configured.build()
}

/**
 * Ensures the `entries_fts` FTS5 virtual table exists on every database open.
 *
 * The table is normally created by MIGRATION_19_20, but won't exist when:
 * - A new installation creates the current schema without running migrations
 * - A test explicitly recreates its disposable database
 *
 * `CREATE VIRTUAL TABLE IF NOT EXISTS` is idempotent — it's a no-op when
 * the table already exists.
 */
private object FtsTableCallback : Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        SearchIndexBootstrapper.bootstrap(connection)
    }
}

private val DATABASE_MIGRATIONS =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_43_44,
        MIGRATION_44_45,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_47_48,
        MIGRATION_48_49,
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
        MIGRATION_55_56,
        MIGRATION_56_57,
        MIGRATION_57_58,
        MIGRATION_58_59,
        MIGRATION_59_60,
        MIGRATION_60_61,
    )
