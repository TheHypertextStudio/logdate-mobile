package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import app.logdate.client.repository.knowledge.ContactImportSummary
import app.logdate.shared.model.PersonOrigin
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_add_suggestion_failed
import logdate.client.feature.core.generated.resources.people_contacts_access_needed
import logdate.client.feature.core.generated.resources.people_directory_empty_state
import logdate.client.feature.core.generated.resources.people_directory_empty_state_with_contacts
import logdate.client.feature.core.generated.resources.people_dismiss_suggestion_failed
import logdate.client.feature.core.generated.resources.people_import_added
import logdate.client.feature.core.generated.resources.people_import_added_and_updated
import logdate.client.feature.core.generated.resources.people_import_failed
import logdate.client.feature.core.generated.resources.people_import_none
import logdate.client.feature.core.generated.resources.people_import_updated
import logdate.client.feature.core.generated.resources.people_refresh_failed
import logdate.client.feature.core.generated.resources.person_origin_contact
import logdate.client.feature.core.generated.resources.person_origin_inferred
import logdate.client.feature.core.generated.resources.person_origin_manual
import logdate.client.feature.core.generated.resources.person_remove_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The outcome of work a People page did or could not do, shown on that page until it is dismissed
 * or replaced by the next outcome.
 */
sealed interface PeopleNotice {
    /** Looking for names in entries and transcripts failed, so the page may show older results. */
    data object RefreshFailed : PeopleNotice

    /** Contacts can't be imported until the system permission is allowed. */
    data object ContactsAccessNeeded : PeopleNotice

    data object ImportContactsFailed : PeopleNotice

    data class ContactsImported(
        val importedCount: Int,
        val updatedCount: Int,
    ) : PeopleNotice

    data object AddSuggestionFailed : PeopleNotice

    data object DismissSuggestionFailed : PeopleNotice

    data object RemovePersonFailed : PeopleNotice
}

internal fun ContactImportSummary.toNotice(): PeopleNotice =
    PeopleNotice.ContactsImported(
        importedCount = importedCount,
        updatedCount = updatedCount,
    )

internal fun PersonOrigin.labelResource(): StringResource =
    when (this) {
        PersonOrigin.MANUAL -> Res.string.person_origin_manual
        PersonOrigin.INFERRED -> Res.string.person_origin_inferred
        PersonOrigin.CONTACT_SELECTED, PersonOrigin.CONTACT_FULL -> Res.string.person_origin_contact
    }

/**
 * What the list says while no one is in it. Contacts are named as a way in only where
 * [contactImportAvailable] says they can be imported.
 */
@Composable
internal fun emptyPeopleListText(contactImportAvailable: Boolean): String {
    if (contactImportAvailable) return stringResource(Res.string.people_directory_empty_state_with_contacts)
    return stringResource(Res.string.people_directory_empty_state)
}

@Composable
internal fun peopleNoticeText(notice: PeopleNotice): String =
    when (notice) {
        PeopleNotice.RefreshFailed -> stringResource(Res.string.people_refresh_failed)
        PeopleNotice.ContactsAccessNeeded -> stringResource(Res.string.people_contacts_access_needed)
        PeopleNotice.ImportContactsFailed -> stringResource(Res.string.people_import_failed)
        is PeopleNotice.ContactsImported -> contactsImportedText(notice)
        PeopleNotice.AddSuggestionFailed -> stringResource(Res.string.people_add_suggestion_failed)
        PeopleNotice.DismissSuggestionFailed -> stringResource(Res.string.people_dismiss_suggestion_failed)
        PeopleNotice.RemovePersonFailed -> stringResource(Res.string.person_remove_failed)
    }

@Composable
private fun contactsImportedText(notice: PeopleNotice.ContactsImported): String {
    if (notice.importedCount == 0 && notice.updatedCount == 0) {
        return stringResource(Res.string.people_import_none)
    }
    if (notice.updatedCount == 0) {
        return stringResource(Res.string.people_import_added, notice.importedCount)
    }
    if (notice.importedCount == 0) {
        return stringResource(Res.string.people_import_updated, notice.updatedCount)
    }
    return stringResource(
        Res.string.people_import_added_and_updated,
        notice.importedCount,
        notice.updatedCount,
    )
}
