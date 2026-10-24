package app.logdate.client.sync.space.mappers

import app.logdate.client.sync.space.SpaceRecordMapper
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.decodeRecord
import app.logdate.client.sync.space.encodeRecord
import app.logdate.client.sync.space.parseDatetime
import app.logdate.client.sync.space.toDatetime
import app.logdate.lexicon.app.logdate.place.Correction
import app.logdate.lexicon.app.logdate.place.Place
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.TravelMode
import kotlinx.serialization.json.JsonObject

/**
 * Maps a [HistoryCorrection] to an `app.logdate.place.correction` record and back. The record key
 * is the correction's id.
 *
 * The device stores every correction's new value as one string; the record gives each kind of
 * value a field of its own type: a label, a reference to a place, a travel mode, or a start or end
 * time. A delete correction has no value in the record, and the device's value for it is always
 * `true`. A value that can't be written in its field's type, a time that isn't in its standard
 * written form, a delete with another value, or a record that sets a field its `field` doesn't
 * change, is refused rather than changed on the way.
 */
object PlaceCorrectionRecordMapper : SpaceRecordMapper<HistoryCorrection> {
    override val collection: String = Correction.TYPE

    private const val MAX_SUPERSEDES = 100
    private const val DELETE_VALUE = "true"

    private val fields =
        NameTable(
            "the field it changes",
            mapOf(
                HistoryField.LABEL.name to "label",
                HistoryField.PLACE.name to "place",
                HistoryField.ACTIVITY.name to "activity",
                HistoryField.START.name to "start",
                HistoryField.END.name to "end",
                HistoryField.DELETE.name to "delete",
            ),
        )

    override fun rkeyOf(value: HistoryCorrection): String = keyFor("$collection/${value.id}", value.id)

    override fun toRecord(value: HistoryCorrection): JsonObject {
        val owner = "$collection/${value.id}"
        keyFor(owner, value.id)
        val supersedes = boundedList(owner, "the corrections it replaces", MAX_SUPERSEDES, value.supersedes)
        val base =
            Correction(
                evidenceId = value.targetEvidenceId,
                field = fields.lexiconName(owner, value.field.name),
                supersedes = supersedes.map { refTo(owner, Correction.TYPE, it) }.ifEmpty { null },
                createdAt = value.createdAt?.toDatetime("$owner createdAt"),
            )
        val record =
            when (value.field) {
                HistoryField.LABEL -> {
                    base.copy(label = value.value)
                }

                HistoryField.PLACE -> {
                    base.copy(place = refTo(owner, Place.TYPE, value.value))
                }

                HistoryField.ACTIVITY -> {
                    base.copy(activity = TravelModes.toLexicon(owner, travelMode(owner, value.value)))
                }

                HistoryField.START -> {
                    base.copy(startsAt = standardTime(owner, value.value).toDatetime("$owner startsAt"))
                }

                HistoryField.END -> {
                    base.copy(endsAt = standardTime(owner, value.value).toDatetime("$owner endsAt"))
                }

                HistoryField.DELETE -> {
                    if (value.value != DELETE_VALUE) throw SpaceRecordMappingException("$owner: a delete has no value to keep")
                    base
                }
            }
        return encodeRecord(Correction.serializer(), record)
    }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): HistoryCorrection {
        val owner = "$collection/$rkey"
        val decoded = decodeRecord(Correction.serializer(), collection, rkey, record)
        val field = HistoryField.valueOf(fields.localName(owner, decoded.field))
        val values =
            listOfNotNull(
                decoded.label?.let { HistoryField.LABEL to it },
                decoded.place?.let { HistoryField.PLACE to keyOfRef(owner, Place.TYPE, it) },
                decoded.activity?.let { HistoryField.ACTIVITY to TravelModes.fromLexicon(owner, it).name },
                decoded.startsAt?.let { HistoryField.START to parseDatetime("$owner startsAt", it).toString() },
                decoded.endsAt?.let { HistoryField.END to parseDatetime("$owner endsAt", it).toString() },
            )
        if (values.any { it.first != field }) throw SpaceRecordMappingException("$owner: it sets a value its field doesn't change")
        val value =
            if (field == HistoryField.DELETE) {
                DELETE_VALUE
            } else {
                values.singleOrNull()?.second ?: throw SpaceRecordMappingException("$owner: it changes a field without giving a value")
            }
        return HistoryCorrection(
            id = keyFor(owner, rkey),
            targetEvidenceId = decoded.evidenceId,
            field = field,
            value = value,
            supersedes = decoded.supersedes.orEmpty().map { keyOfRef(owner, Correction.TYPE, it) },
            createdAt = decoded.createdAt?.let { parseDatetime("$owner createdAt", it) },
        )
    }

    /**
     * [corrections] with each `supersedes` list cut down to the corrections not already replaced
     * through another one it lists, as the lexicon asks when moving today's corrections over.
     *
     * Today every correction lists every earlier correction to the same field. When a correction
     * lists both B and A, and B already replaces A, listing A again says nothing new: A stays
     * replaced through B, and corrections are never deleted. Dropping those keeps the list short
     * without changing which corrections are replaced. A correction that lists one this set doesn't
     * hold keeps it.
     */
    fun withoutRedundantSupersedes(corrections: Collection<HistoryCorrection>): List<HistoryCorrection> {
        val byId = corrections.associateBy { it.id }
        val reachable = mutableMapOf<String, Set<String>>()

        fun replacedThrough(id: String): Set<String> {
            reachable[id]?.let { return it }
            val found = mutableSetOf<String>()
            val pending = ArrayDeque(byId[id]?.supersedes.orEmpty())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (next == id || !found.add(next)) continue
                pending += byId[next]?.supersedes.orEmpty()
            }
            return found.also { reachable[id] = it }
        }

        return corrections.map { correction ->
            val listed = correction.supersedes.distinct()
            // Two corrections that replace each other (a fault, but possible in old data) keep both.
            val implied =
                listed.filter { replaced ->
                    listed.any { other -> other != replaced && replaced in replacedThrough(other) && other !in replacedThrough(replaced) }
                }
            correction.copy(supersedes = listed - implied.toSet())
        }
    }

    private fun travelMode(
        owner: String,
        value: String,
    ): TravelMode =
        TravelMode.entries.firstOrNull { it.name == value } ?: throw SpaceRecordMappingException("$owner: its value isn't a travel mode")

    private fun standardTime(
        owner: String,
        value: String,
    ): kotlin.time.Instant {
        val time = kotlin.time.Instant.parseOrNull(value)
        if (time == null || time.toString() != value) {
            throw SpaceRecordMappingException(
                "$owner: its value isn't a time in the standard written form, so a record can't keep it unchanged",
            )
        }
        return time
    }
}
