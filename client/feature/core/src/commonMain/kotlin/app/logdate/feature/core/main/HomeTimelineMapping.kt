
package app.logdate.feature.core.main

import app.logdate.client.awareness.daylight.DaylightClassifier
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.recommendation.HomeRecommendation
import app.logdate.client.domain.timeline.Moment
import app.logdate.client.domain.timeline.TimelineDay
import app.logdate.client.domain.timeline.TimelinePlaceVisit
import app.logdate.client.domain.timeline.capturedDuring
import app.logdate.client.domain.timeline.capturedOnWatch
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.isCapturedOnWatch
import app.logdate.feature.timeline.ui.notice
import app.logdate.feature.timeline.ui.toUiText
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Person
import app.logdate.ui.location.PlaceUiState
import app.logdate.ui.profiles.PersonUiState
import app.logdate.ui.profiles.toUiState
import app.logdate.ui.timeline.AudioNoteUiState
import app.logdate.ui.timeline.ImageNoteUiState
import app.logdate.ui.timeline.JournalBadgeUiState
import app.logdate.ui.timeline.MediaObjectUiState
import app.logdate.ui.timeline.MomentAudioUiState
import app.logdate.ui.timeline.MomentMediaUiState
import app.logdate.ui.timeline.MomentUiState
import app.logdate.ui.timeline.TextNoteUiState
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineSuggestionBlock
import app.logdate.ui.timeline.VideoNoteUiState
import app.logdate.ui.timeline.createSemanticTimelineDayUiState
import app.logdate.ui.timeline.toDayEventUiState
import kotlin.uuid.Uuid

private fun TimelinePlaceVisit.toUiState(): PlaceUiState =
    PlaceUiState(
        id = id,
        title = name,
        latitude = latitude,
        longitude = longitude,
    )

internal fun TimelineDay.toHomeTimelineUiState(
    overrideNotes: List<JournalNote> = entries,
    membershipMap: Map<Uuid, List<Journal>> = emptyMap(),
): TimelineDayUiState {
    val noteUiStates = overrideNotes.toUiState(membershipMap)
    val placeUiStates = placesVisited.map { place -> place.toUiState() }
    val peopleUiStates = people.map(Person::toUiState)
    val momentUiStates = moments.toMomentUiStates(peopleUiStates)
    val eventUiStates = if (FeatureFlag.EVENTS.availableForLaunch) events.map { it.toDayEventUiState() } else emptyList()

    return createSemanticTimelineDayUiState(
        summary = tldr,
        summaryNotice = summaryUnavailableReason?.notice,
        date = date,
        moments = momentUiStates,
        people = peopleUiStates,
        notes = noteUiStates,
        placesVisited = placeUiStates,
        events = eventUiStates,
        isLoadingSummary = isSummaryPending && marks.isEmpty(),
        isLoadingPeople = people.isEmpty() && isSummaryPending && marks.isEmpty(),
        pendingMarks = marks,
    )
}

private fun List<Moment>.toMomentUiStates(dayPeople: List<PersonUiState>): List<MomentUiState> {
    val heroIndex =
        indices.maxByOrNull { i ->
            val m = this[i]
            m.media.size * 3 + m.textFragments.size * 2 + m.audio.size
        } ?: 0
    return mapIndexed { index, moment ->
        moment.toMomentUiState(isHero = index == heroIndex, dayPeople = dayPeople)
    }
}

private fun Moment.toMomentUiState(
    isHero: Boolean,
    dayPeople: List<PersonUiState>,
): MomentUiState {
    val timeOfDay = DaylightClassifier().classifyWithoutLocation(estimatedStart)
    val resolvedPeople =
        people.mapNotNull { name ->
            dayPeople.find { it.name.equals(name, ignoreCase = true) }
        }
    return MomentUiState(
        id = id.toString(),
        capturedAt = estimatedStart,
        sourceNoteIds = sourceNotes.map { it.uid }.toSet(),
        label = label,
        labelPlaceName = labelPlace?.name,
        timeOfDay = timeOfDay,
        textSnippet = textFragments.firstOrNull()?.text,
        textNoteId =
            textFragments.firstOrNull()?.sourceNoteId?.takeIf { sourceId ->
                sourceNotes.any { note -> note.uid == sourceId && note is JournalNote.Text }
            },
        media = media.map { MomentMediaUiState(uri = it.uri, isVideo = it.isVideo, noteId = it.sourceNoteId) },
        handwriting =
            sourceNotes
                .filterIsInstance<JournalNote.Handwriting>()
                .toUiState()
                .filterIsInstance<app.logdate.ui.timeline.HandwritingNoteUiState>(),
        audio =
            audio.firstOrNull()?.let {
                MomentAudioUiState(
                    uri = it.uri,
                    durationMs = it.durationMs,
                    noteId = it.sourceNoteId,
                    recordedAt = estimatedStart,
                )
            },
        places = places.map { PlaceUiState(id = it.id, title = it.name, latitude = it.latitude, longitude = it.longitude) },
        people = resolvedPeople,
        isHero = isHero,
        capturedOnWatch = capturedOnWatch(),
        capturedDuring = capturedDuring(),
    )
}

private fun JournalNote.Handwriting.toHandwritingUiState(
    noteJournals: List<JournalBadgeUiState>,
): app.logdate.ui.timeline.HandwritingNoteUiState =
    app.logdate.ui.timeline.HandwritingNoteUiState(
        uid,
        document,
        creationTimestamp,
        noteJournals,
        isCapturedOnWatch,
        unreadable = unreadableContent != null,
        capturedDuring = capturedDuring,
    )

private fun List<JournalNote>.toUiState(membershipMap: Map<Uuid, List<Journal>> = emptyMap()): List<app.logdate.ui.timeline.NoteUiState> =
    sortedByDescending { note -> note.creationTimestamp }.map { note ->
        val noteJournals =
            membershipMap[note.uid]
                .orEmpty()
                .map { JournalBadgeUiState(journalId = it.id, title = it.title) }
        when (note) {
            is JournalNote.Handwriting -> {
                note.toHandwritingUiState(noteJournals)
            }

            is JournalNote.Text -> {
                TextNoteUiState(
                    noteId = note.uid,
                    text = note.content,
                    timestamp = note.creationTimestamp,
                    journals = noteJournals,
                    capturedOnWatch = note.isCapturedOnWatch,
                    capturedDuring = note.capturedDuring,
                )
            }

            is JournalNote.Image -> {
                ImageNoteUiState(
                    noteId = note.uid,
                    uri = note.mediaRef,
                    timestamp = note.creationTimestamp,
                    caption = note.caption,
                    presentation = note.presentation,
                    journals = noteJournals,
                    capturedOnWatch = note.isCapturedOnWatch,
                    capturedDuring = note.capturedDuring,
                )
            }

            is JournalNote.Audio -> {
                AudioNoteUiState(
                    noteId = note.uid,
                    uri = note.mediaRef,
                    timestamp = note.creationTimestamp,
                    duration = note.durationMs,
                    journals = noteJournals,
                    capturedOnWatch = note.isCapturedOnWatch,
                    capturedDuring = note.capturedDuring,
                )
            }

            is JournalNote.Video -> {
                VideoNoteUiState(
                    noteId = note.uid,
                    uri = note.mediaRef,
                    timestamp = note.creationTimestamp,
                    caption = note.caption,
                    journals = noteJournals,
                    capturedOnWatch = note.isCapturedOnWatch,
                    capturedDuring = note.capturedDuring,
                )
            }
        }
    }

internal fun HomeRecommendation.toTimelineSuggestionBlock(): TimelineSuggestionBlock? =
    when (this) {
        is HomeRecommendation.EmptyDay -> {
            TimelineSuggestionBlock.EmptyDay(
                locationName = location?.toUiText(),
            )
        }

        is HomeRecommendation.CompleteYourDraft -> {
            TimelineSuggestionBlock.CompleteDraft(
                draftId = draftId.toString(),
            )
        }

        is HomeRecommendation.MemoryRecall -> {
            TimelineSuggestionBlock.MemoryRecall(
                memoryDate = date,
                title = summary,
                people = people,
                mediaUris = mediaUris.map { uri -> MediaObjectUiState(uid = uri, uri = uri) },
                isAiGenerated = isAiGenerated,
            )
        }

        is HomeRecommendation.UpcomingEvent -> {
            TimelineSuggestionBlock.UpcomingEvent(
                eventId = eventId.toString(),
                title = title,
                startTime = startTime,
                placeName = placeName,
            )
        }

        is HomeRecommendation.AfterActivity -> {
            TimelineSuggestionBlock.AfterActivity(minutes = minutes, trip = trip)
        }

        HomeRecommendation.MorningStart -> {
            TimelineSuggestionBlock.MorningStart
        }

        HomeRecommendation.None -> {
            null
        }
    }
