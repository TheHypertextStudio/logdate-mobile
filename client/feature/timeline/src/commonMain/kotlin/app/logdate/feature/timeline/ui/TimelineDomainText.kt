package app.logdate.feature.timeline.ui

import app.logdate.client.domain.recommendation.CurrentLocationDescription
import app.logdate.client.domain.timeline.TimelineSummaryUnavailableReason
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.feature.timeline.generated.resources.Res
import logdate.client.feature.timeline.generated.resources.timeline_suggestion_location_area
import logdate.client.feature.timeline.generated.resources.timeline_suggestion_location_neighborhood
import logdate.client.feature.timeline.generated.resources.timeline_suggestion_location_street
import logdate.client.feature.timeline.generated.resources.timeline_summary_no_network
import logdate.client.feature.timeline.generated.resources.timeline_summary_unavailable
import org.jetbrains.compose.resources.StringResource

/**
 * The words a day's summary section shows in place of a summary that could not be made.
 */
val TimelineSummaryUnavailableReason.notice: StringResource
    get() =
        when (this) {
            TimelineSummaryUnavailableReason.NO_NETWORK -> Res.string.timeline_summary_no_network
            TimelineSummaryUnavailableReason.UNAVAILABLE -> Res.string.timeline_summary_unavailable
        }

/**
 * Describes where the user is now on the empty-day suggestion card.
 */
fun CurrentLocationDescription.toUiText(): UiText =
    when (this) {
        is CurrentLocationDescription.NamedPlace -> {
            name.toUiText()
        }

        is CurrentLocationDescription.Street -> {
            Res.string.timeline_suggestion_location_street.toUiText(street, locality)
        }

        is CurrentLocationDescription.Neighborhood -> {
            Res.string.timeline_suggestion_location_neighborhood.toUiText(neighborhood, locality)
        }

        is CurrentLocationDescription.Area -> {
            Res.string.timeline_suggestion_location_area.toUiText(name)
        }
    }
