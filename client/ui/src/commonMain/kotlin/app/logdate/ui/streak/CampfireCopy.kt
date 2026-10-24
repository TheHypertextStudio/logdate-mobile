package app.logdate.ui.streak

import androidx.compose.runtime.Composable
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.campfire_burning_headline
import logdate.client.ui.generated.resources.campfire_burning_supporting
import logdate.client.ui.generated.resources.campfire_chip_description
import logdate.client.ui.generated.resources.campfire_chip_description_out
import logdate.client.ui.generated.resources.campfire_chip_description_unlit
import logdate.client.ui.generated.resources.campfire_embers_headline
import logdate.client.ui.generated.resources.campfire_embers_supporting
import logdate.client.ui.generated.resources.campfire_out_headline
import logdate.client.ui.generated.resources.campfire_out_supporting
import logdate.client.ui.generated.resources.campfire_unlit_headline
import logdate.client.ui.generated.resources.campfire_unlit_supporting
import logdate.client.ui.generated.resources.campfire_waiting_headline
import logdate.client.ui.generated.resources.campfire_waiting_supporting
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one-line summary of where the fire stands, such as "Down to embers".
 */
@Composable
fun campfireHeadline(presentation: CampfirePresentation): String =
    when (presentation.phase) {
        CampfirePhase.UNLIT -> {
            stringResource(Res.string.campfire_unlit_headline)
        }

        CampfirePhase.BURNING -> {
            if (presentation.loggedToday) {
                stringResource(Res.string.campfire_burning_headline)
            } else {
                stringResource(Res.string.campfire_waiting_headline)
            }
        }

        CampfirePhase.EMBERS -> {
            stringResource(Res.string.campfire_embers_headline)
        }

        CampfirePhase.OUT -> {
            stringResource(Res.string.campfire_out_headline)
        }
    }

/**
 * The line under the headline that says what the fire means or what keeps it going.
 */
@Composable
fun campfireSupportingLine(presentation: CampfirePresentation): String =
    when (presentation.phase) {
        CampfirePhase.UNLIT -> {
            stringResource(Res.string.campfire_unlit_supporting)
        }

        CampfirePhase.BURNING -> {
            if (presentation.loggedToday) {
                pluralStringResource(Res.plurals.campfire_burning_supporting, presentation.runDays, presentation.runDays)
            } else {
                stringResource(Res.string.campfire_waiting_supporting, presentation.runDays + 1)
            }
        }

        CampfirePhase.EMBERS -> {
            stringResource(Res.string.campfire_embers_supporting, presentation.runDays)
        }

        CampfirePhase.OUT -> {
            pluralStringResource(
                Res.plurals.campfire_out_supporting,
                presentation.longestRunDays,
                presentation.longestRunDays,
            )
        }
    }

/**
 * A short spoken description of the fire for compact surfaces that show only a count.
 */
@Composable
fun campfireShortDescription(presentation: CampfirePresentation): String =
    when (presentation.phase) {
        CampfirePhase.UNLIT -> {
            stringResource(Res.string.campfire_chip_description_unlit)
        }

        CampfirePhase.OUT -> {
            stringResource(Res.string.campfire_chip_description_out)
        }

        CampfirePhase.BURNING, CampfirePhase.EMBERS -> {
            pluralStringResource(Res.plurals.campfire_chip_description, presentation.runDays, presentation.runDays)
        }
    }
