package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import app.logdate.client.repository.knowledge.ContactImportSummary
import app.logdate.client.repository.knowledge.PeopleContactsAccessMode
import app.logdate.shared.model.PersonOrigin
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_access_mode_full
import logdate.client.feature.core.generated.resources.people_access_mode_none
import logdate.client.feature.core.generated.resources.people_access_mode_selected
import logdate.client.feature.core.generated.resources.people_disabled_import_notice
import logdate.client.feature.core.generated.resources.people_import_added
import logdate.client.feature.core.generated.resources.people_import_added_and_updated
import logdate.client.feature.core.generated.resources.people_import_failed
import logdate.client.feature.core.generated.resources.people_import_none
import logdate.client.feature.core.generated.resources.people_import_updated
import logdate.client.feature.core.generated.resources.people_selected_import_failed
import logdate.client.feature.core.generated.resources.people_settings_update_failed
import logdate.client.feature.core.generated.resources.person_origin_contact_full
import logdate.client.feature.core.generated.resources.person_origin_contact_selected
import logdate.client.feature.core.generated.resources.person_origin_inferred
import logdate.client.feature.core.generated.resources.person_origin_manual
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

sealed interface PeopleSettingsNotice {
    data object SettingsUpdateFailed : PeopleSettingsNotice

    data object PeopleDisabled : PeopleSettingsNotice

    data object ImportAllContactsFailed : PeopleSettingsNotice

    data object ImportSelectedContactsFailed : PeopleSettingsNotice

    data class ContactsImported(
        val importedCount: Int,
        val updatedCount: Int,
    ) : PeopleSettingsNotice
}

internal fun ContactImportSummary.toNotice(): PeopleSettingsNotice =
    PeopleSettingsNotice.ContactsImported(
        importedCount = importedCount,
        updatedCount = updatedCount,
    )

internal fun PeopleContactsAccessMode.labelResource(): StringResource =
    when (this) {
        PeopleContactsAccessMode.NONE -> Res.string.people_access_mode_none
        PeopleContactsAccessMode.SELECTED -> Res.string.people_access_mode_selected
        PeopleContactsAccessMode.FULL -> Res.string.people_access_mode_full
    }

internal fun PersonOrigin.labelResource(): StringResource =
    when (this) {
        PersonOrigin.MANUAL -> Res.string.person_origin_manual
        PersonOrigin.INFERRED -> Res.string.person_origin_inferred
        PersonOrigin.CONTACT_SELECTED -> Res.string.person_origin_contact_selected
        PersonOrigin.CONTACT_FULL -> Res.string.person_origin_contact_full
    }

@Composable
internal fun peopleSettingsNoticeText(notice: PeopleSettingsNotice): String =
    when (notice) {
        PeopleSettingsNotice.SettingsUpdateFailed -> {
            stringResource(Res.string.people_settings_update_failed)
        }

        PeopleSettingsNotice.PeopleDisabled -> {
            stringResource(Res.string.people_disabled_import_notice)
        }

        PeopleSettingsNotice.ImportAllContactsFailed -> {
            stringResource(Res.string.people_import_failed)
        }

        PeopleSettingsNotice.ImportSelectedContactsFailed -> {
            stringResource(Res.string.people_selected_import_failed)
        }

        is PeopleSettingsNotice.ContactsImported -> {
            when {
                notice.importedCount == 0 && notice.updatedCount == 0 -> {
                    stringResource(Res.string.people_import_none)
                }

                notice.updatedCount == 0 -> {
                    stringResource(Res.string.people_import_added, notice.importedCount)
                }

                notice.importedCount == 0 -> {
                    stringResource(Res.string.people_import_updated, notice.updatedCount)
                }

                else -> {
                    stringResource(
                        Res.string.people_import_added_and_updated,
                        notice.importedCount,
                        notice.updatedCount,
                    )
                }
            }
        }
    }
