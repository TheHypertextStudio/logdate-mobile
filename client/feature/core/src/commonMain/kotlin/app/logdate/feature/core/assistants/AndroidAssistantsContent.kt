@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.assistants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.android_assistants_entries
import logdate.client.feature.core.generated.resources.android_assistants_locked
import logdate.client.feature.core.generated.resources.android_assistants_off
import logdate.client.feature.core.generated.resources.android_assistants_people
import logdate.client.feature.core.generated.resources.android_assistants_places
import logdate.client.feature.core.generated.resources.android_assistants_sees_one
import logdate.client.feature.core.generated.resources.android_assistants_sees_three
import logdate.client.feature.core.generated.resources.android_assistants_sees_two
import logdate.client.feature.core.generated.resources.android_assistants_starts
import logdate.client.feature.core.generated.resources.android_assistants_subtitle
import logdate.client.feature.core.generated.resources.android_assistants_title
import logdate.client.feature.core.generated.resources.assistant_connect_never_writes
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Privacy & security › Android assistants: what assistants on this phone, such as Gemini,
 * may see and do in LogDate. Nothing is on until the person turns it on, and the switches are the
 * same ones a connected assistant offers.
 */
@Composable
fun AndroidAssistantsContent(
    journals: List<AssistantJournalOption>,
    access: AssistantAccessSelection,
    onAccessChange: (AssistantAccessSelection) -> Unit,
    onBack: () -> Unit,
) {
    AssistantPage(navigationIcon = { BackButton(onBack) }) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(Res.string.android_assistants_title),
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(Res.string.android_assistants_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        assistantAccessTiles(AssistantCapability.entries.toSet(), journals, access, enabled = true, onAccessChange)
        item(key = "facts") {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    AssistantFact(Icons.Rounded.EditOff, stringResource(Res.string.assistant_connect_never_writes))
                    AssistantFact(Icons.Rounded.Lock, stringResource(Res.string.android_assistants_locked))
                }
            }
        }
    }
}

/** One line for Settings: what assistants on this phone may see and do, or that they're off. */
@Composable
fun androidAssistantsSummary(capabilities: Set<AssistantCapability>): String {
    if (capabilities.isEmpty()) return stringResource(Res.string.android_assistants_off)
    val seen =
        listOfNotNull(
            stringResource(Res.string.android_assistants_entries).takeIf { AssistantCapability.READ_MEMORIES in capabilities },
            stringResource(Res.string.android_assistants_places).takeIf { AssistantCapability.READ_PLACES in capabilities },
            stringResource(Res.string.android_assistants_people).takeIf { AssistantCapability.READ_PEOPLE in capabilities },
        )
    val sees =
        when (seen.size) {
            0 -> null
            1 -> stringResource(Res.string.android_assistants_sees_one, seen[0])
            2 -> stringResource(Res.string.android_assistants_sees_two, seen[0], seen[1])
            else -> stringResource(Res.string.android_assistants_sees_three, seen[0], seen[1], seen[2])
        }
    val starts = stringResource(Res.string.android_assistants_starts).takeIf { AssistantCapability.START_CAPTURE in capabilities }
    return listOfNotNull(sees, starts).joinToString(" · ")
}
