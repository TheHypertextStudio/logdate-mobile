@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.platform.currentPlatform
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * The list of people in the directory.
 *
 * @param contactImportAvailable whether this platform can import contacts, which decides whether
 *   the empty list names contacts as a way to fill it
 */
@Composable
fun PeopleDirectoryScreen(
    onBack: () -> Unit,
    onOpenPerson: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PeopleDirectoryViewModel = koinViewModel(),
    contactImportAvailable: Boolean = currentPlatform.supportsContactImport(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    PeopleDirectoryContent(
        people = people,
        notice = notice,
        onBack = onBack,
        onOpenPerson = onOpenPerson,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
        contactImportAvailable = contactImportAvailable,
    )
}

/**
 * Stateless list of people. Each row says how the person came to be in the list and opens their
 * page.
 *
 * @param people the people in the list, or `null` while the list is still being read, when neither
 *   rows nor the empty message are shown
 * @param contactImportAvailable whether the empty message names contacts as a way to fill the list
 */
@Composable
fun PeopleDirectoryContent(
    people: List<PeopleDirectoryViewModel.PersonRow>?,
    notice: PeopleNotice?,
    onBack: () -> Unit,
    onOpenPerson: (Uuid) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    contactImportAvailable: Boolean = false,
) {
    SettingsScaffold(
        title = stringResource(Res.string.people_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (notice != null) {
            item {
                PeopleNoticeCard(
                    notice = notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        if (people == null) return@SettingsScaffold

        if (people.isEmpty()) {
            item {
                Text(
                    text = emptyPeopleListText(contactImportAvailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            return@SettingsScaffold
        }

        item {
            MaterialContainer(
                modifier = Modifier.padding(horizontal = Spacing.lg),
            ) {
                people.forEach { person ->
                    SettingsNavigationItem(
                        title = person.name,
                        description = stringResource(person.origin.labelResource()),
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        onClick = { onOpenPerson(person.id) },
                    )
                }
            }
        }
    }
}
