package app.logdate.client.repository.knowledge

import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.flow.Flow

enum class PeopleContactsAccessMode {
    NONE,
    SELECTED,
    FULL,
}

data class ContactImportSummary(
    val importedCount: Int,
    val updatedCount: Int,
)

interface PeopleContactsRepository {
    fun observeImportedPeopleCount(origin: PersonOrigin): Flow<Int>

    suspend fun importContacts(
        contacts: List<DeviceContact>,
        origin: PersonOrigin,
    ): ContactImportSummary
}
