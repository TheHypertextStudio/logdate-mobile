@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.permissions.ContactsPermissionState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_browse_description
import logdate.client.feature.core.generated.resources.people_browse_label
import logdate.client.feature.core.generated.resources.people_import_all_contacts
import logdate.client.feature.core.generated.resources.people_import_description
import logdate.client.feature.core.generated.resources.people_import_title
import logdate.client.feature.core.generated.resources.people_importing_contacts
import logdate.client.feature.core.generated.resources.people_review_description
import logdate.client.feature.core.generated.resources.people_review_label
import logdate.client.feature.core.generated.resources.people_settings_description
import logdate.client.feature.core.generated.resources.people_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The People page in Settings. It is reachable only while People is turned on in Labs.
 *
 * Opening the page looks for names in entries and transcripts again. An import asked for without
 * contacts access requests the permission and continues by itself once it is granted.
 */
@Composable
fun PeopleSettingsScreen(
    onBack: () -> Unit,
    onBrowsePeople: () -> Unit,
    onOpenReviewInbox: () -> Unit,
    contactsPermissionState: ContactsPermissionState,
    modifier: Modifier = Modifier,
    viewModel: PeopleSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.refreshPeople()
    }

    LaunchedEffect(contactsPermissionState.hasPermission, contactsPermissionState.permissionRequested) {
        viewModel.onContactsAccessChanged(
            granted = contactsPermissionState.hasPermission,
            answered = contactsPermissionState.permissionRequested,
        )
    }

    PeopleSettingsContent(
        uiState = uiState,
        onBack = onBack,
        onBrowsePeople = onBrowsePeople,
        onOpenReviewInbox = onOpenReviewInbox,
        onImportAllContacts = {
            if (contactsPermissionState.hasPermission) {
                viewModel.importAllContacts()
            } else {
                viewModel.importWhenContactsAllowed(deniedBefore = contactsPermissionState.permissionRequested)
                contactsPermissionState.requestPermission()
            }
        },
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

/**
 * Stateless People page body.
 *
 * A phone, tablet or desktop window shows one list. A foldable held open in book posture puts the
 * description, the contact import and its result on the start pane, and the rows that open the
 * list and the suggestions on the end pane.
 */
@Composable
fun PeopleSettingsContent(
    uiState: PeopleSettingsViewModel.UiState,
    onBack: () -> Unit,
    onBrowsePeople: () -> Unit,
    onOpenReviewInbox: () -> Unit,
    onImportAllContacts: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                PeopleDescription()
                if (uiState.contactImportAvailable) {
                    ContactImportCard(
                        isImporting = uiState.isImporting,
                        onImportAllContacts = onImportAllContacts,
                    )
                }
                uiState.notice?.let { notice ->
                    PeopleNoticeCard(
                        notice = notice,
                        onDismiss = onDismissMessage,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                PeopleListRows(
                    uiState = uiState,
                    onBrowsePeople = onBrowsePeople,
                    onOpenReviewInbox = onOpenReviewInbox,
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.people_title),
                onBack = onBack,
            ) {
                item { PeopleDescription() }

                item {
                    PeopleListRows(
                        uiState = uiState,
                        onBrowsePeople = onBrowsePeople,
                        onOpenReviewInbox = onOpenReviewInbox,
                    )
                }

                if (uiState.contactImportAvailable) {
                    item {
                        ContactImportCard(
                            isImporting = uiState.isImporting,
                            onImportAllContacts = onImportAllContacts,
                        )
                    }
                }

                uiState.notice?.let { notice ->
                    item {
                        PeopleNoticeCard(
                            notice = notice,
                            onDismiss = onDismissMessage,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Says where the list comes from and where it is stored.
 */
@Composable
private fun PeopleDescription() {
    Text(
        text = stringResource(Res.string.people_settings_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
}

/**
 * The rows that open the list of people and, while names wait for review, the suggestions.
 */
@Composable
private fun PeopleListRows(
    uiState: PeopleSettingsViewModel.UiState,
    onBrowsePeople: () -> Unit,
    onOpenReviewInbox: () -> Unit,
) {
    MaterialContainer(
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.people_browse_label),
            description =
                if (uiState.totalPeopleCount == 0) {
                    emptyPeopleListText(uiState.contactImportAvailable)
                } else {
                    pluralStringResource(
                        Res.plurals.people_browse_description,
                        uiState.totalPeopleCount,
                        uiState.totalPeopleCount,
                    )
                },
            icon = { Icon(Icons.Default.People, contentDescription = null) },
            onClick = onBrowsePeople,
            enabled = uiState.totalPeopleCount > 0,
        )
        if (uiState.pendingReviewCount > 0) {
            SettingsNavigationItem(
                title = stringResource(Res.string.people_review_label),
                description =
                    pluralStringResource(
                        Res.plurals.people_review_description,
                        uiState.pendingReviewCount,
                        uiState.pendingReviewCount,
                    ),
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                onClick = onOpenReviewInbox,
            )
        }
    }
}

/**
 * Says what a contact import copies and starts it. Shown only where contacts can be imported.
 */
@Composable
private fun ContactImportCard(
    isImporting: Boolean,
    onImportAllContacts: () -> Unit,
) {
    MaterialContainer(
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.people_import_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.people_import_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onImportAllContacts,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Text(
                    text = stringResource(Res.string.people_import_all_contacts),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            if (isImporting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.people_importing_contacts),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
