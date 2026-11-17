package app.logdate.client.database.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Junction
import androidx.room.Relation
import kotlin.uuid.Uuid

@Entity(
    tableName = "journal_notes",
    primaryKeys = ["id", "uid"],
)
data class JournalNoteCrossRef(
    @ColumnInfo(name = "id", index = true)
    val journalId: Uuid,
    @ColumnInfo(name = "uid", index = true)
    val noteId: Uuid,
)

/**
 * A data container for a journal and its associated notes.
 */
data class JournalWithNotes(
    @Embedded
    val journal: JournalEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "uid",
        associateBy = Junction(JournalNoteCrossRef::class),
    )
    val textNotes: List<TextNoteEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "uid",
        associateBy = Junction(JournalNoteCrossRef::class),
    )
    val imageNotes: List<ImageNoteEntity>,
//    val audioNotes: List<AudioNoteEntity>,
//    val videoNotes: List<VideoNoteEntity>,
)
