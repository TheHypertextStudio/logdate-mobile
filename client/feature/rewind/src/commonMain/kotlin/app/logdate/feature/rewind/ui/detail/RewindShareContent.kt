package app.logdate.feature.rewind.ui.detail

import app.logdate.client.text.resolve
import app.logdate.feature.rewind.ui.AudioNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.BasicTextRewindPanelUiState
import app.logdate.feature.rewind.ui.BigStatisticRewindPanelUiState
import app.logdate.feature.rewind.ui.HighlightedQuoteRewindPanelUiState
import app.logdate.feature.rewind.ui.ImageNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.ImageRewindPanelUiState
import app.logdate.feature.rewind.ui.LocationMapRewindPanelUiState
import app.logdate.feature.rewind.ui.NarrativeContextRewindPanelUiState
import app.logdate.feature.rewind.ui.ReflectionPromptRewindPanelUiState
import app.logdate.feature.rewind.ui.RewindPanelUiState
import app.logdate.feature.rewind.ui.SubtitledRewindPanelUiState
import app.logdate.feature.rewind.ui.TextNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.TransitionRewindPanelUiState

/**
 * The visual half of a share — either an existing media file the panel already has, a quote
 * card the share pipeline can render from text, or nothing visual at all.
 */
sealed interface RewindShareVisual {
    /** An image or video already on disk that the panel exposes via [uri]. */
    data class ExistingMedia(
        val uri: String,
    ) : RewindShareVisual

    /**
     * A request to render the user's words onto a styled card. The renderer can use [accentSeed]
     * to vary card-to-card colors and [dateLabel] to surface when the moment happened.
     */
    data class Quote(
        val text: String,
        val dateLabel: String? = null,
        val accentSeed: Int = 0,
    ) : RewindShareVisual

    /** No visual: text-only share. */
    data object None : RewindShareVisual
}

/**
 * Plain-data summary of a rewind panel suitable for handing to the system share sheet.
 *
 * The shared text is what most apps will surface (Messages, Mail, social posts), and
 * [visual] gives image-aware targets like Instagram or Photos something richer to attach.
 */
data class RewindShareContent(
    val text: String,
    val visual: RewindShareVisual = RewindShareVisual.None,
    val dateFormatted: String? = null,
)

/**
 * Everything the share path needs to launch a system share sheet for a single rewind panel.
 *
 * The composable assembles this from a [RewindShareContent] (panel-derived) plus localized
 * strings it resolves through compose resources, then hands it to the view model. The view
 * model owns the platform `SharingLauncher` and quote card renderer dependencies and just
 * decodes the request — no string formatting, no panel introspection.
 */
data class RewindShareRequest(
    val text: String,
    val title: String,
    val chooserTitle: String,
    val visual: RewindShareVisual = RewindShareVisual.None,
)

/**
 * Everything the share path needs to launch a system share sheet for a rewind's stats summary.
 *
 * The composable resolves every label through compose resources before assembling this object;
 * the view model just plugs the values into the [app.logdate.client.sharing.RewindStatsSummary]
 * the renderer expects. There are no nullable text fields here on purpose — empty strings would
 * draw blank space, so the composable should omit a label entirely if it doesn't apply.
 */
data class RewindStatsShareRequest(
    val text: String,
    val title: String,
    val chooserTitle: String,
    val subtitle: String,
    val entriesLabel: String,
    val photosLabel: String,
    val peopleLabel: String,
    val themeHeadingLabel: String,
    val locationHeadingLabel: String,
)

/**
 * Extracts shareable content from a rewind panel. Returns null when the panel has nothing
 * meaningful to share (e.g. a pure decorative big-stat card with empty fields).
 *
 * Suspends to put the panel's text into words for the share sheet, outside composition.
 */
suspend fun RewindPanelUiState.toShareContent(): RewindShareContent? =
    when (this) {
        is TextNoteRewindPanelUiState -> {
            val date = dateFormatted.resolve()
            RewindShareContent(
                text = content,
                visual =
                    RewindShareVisual.Quote(
                        text = content,
                        dateLabel = date,
                        accentSeed = sourceId.hashCode(),
                    ),
                dateFormatted = date,
            )
        }

        is ImageRewindPanelUiState -> {
            val date = dateFormatted.resolve()
            RewindShareContent(
                text = caption ?: date,
                visual = RewindShareVisual.ExistingMedia(imageUri),
                dateFormatted = date,
            )
        }

        is ImageNoteRewindPanelUiState -> {
            RewindShareContent(
                text = caption ?: dateFormatted,
                visual = RewindShareVisual.ExistingMedia(imageUri),
                dateFormatted = dateFormatted,
            )
        }

        is NarrativeContextRewindPanelUiState -> {
            RewindShareContent(
                text = contextText,
                visual =
                    if (backgroundImageUri != null) {
                        RewindShareVisual.ExistingMedia(backgroundImageUri)
                    } else {
                        RewindShareVisual.Quote(text = contextText, accentSeed = sourceId.hashCode())
                    },
            )
        }

        is TransitionRewindPanelUiState -> {
            RewindShareContent(
                text = transitionText,
                visual = RewindShareVisual.Quote(text = transitionText, accentSeed = sourceId.hashCode()),
            )
        }

        is ReflectionPromptRewindPanelUiState -> {
            RewindShareContent(
                text = invitation,
                visual = RewindShareVisual.Quote(text = invitation, accentSeed = accentSeed),
            )
        }

        is HighlightedQuoteRewindPanelUiState -> {
            RewindShareContent(
                text = text,
                visual = RewindShareVisual.Quote(text = text, accentSeed = accentSeed),
            )
        }

        is SubtitledRewindPanelUiState -> {
            RewindShareContent(
                text = "$title — ${subtitle.resolve()}",
                visual = backgroundUri?.let { RewindShareVisual.ExistingMedia(it) } ?: RewindShareVisual.None,
            )
        }

        is BasicTextRewindPanelUiState -> {
            text.takeIf { it.isNotBlank() }?.let {
                RewindShareContent(text = it, visual = RewindShareVisual.Quote(text = it))
            }
        }

        is BigStatisticRewindPanelUiState -> {
            if (statistic.isBlank()) {
                null
            } else {
                val unitsText = units.resolve()
                val unitsSuffix = if (unitsText.isNotBlank()) " $unitsText" else ""
                RewindShareContent(text = "$title: $statistic$unitsSuffix — ${description.resolve()}")
            }
        }

        is LocationMapRewindPanelUiState -> {
            // Location maps don't have a useful share format yet — sharing the canvas would
            // require rasterizing on demand. Skip the share path entirely until that ships.
            null
        }

        is AudioNoteRewindPanelUiState -> {
            val date = dateFormatted.resolve()
            RewindShareContent(
                text = transcriptionText ?: date,
                visual = RewindShareVisual.ExistingMedia(uri),
                dateFormatted = date,
            )
        }
    }
