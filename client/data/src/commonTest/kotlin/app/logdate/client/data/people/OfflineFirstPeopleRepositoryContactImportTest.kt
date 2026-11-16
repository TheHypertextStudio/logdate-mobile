package app.logdate.client.data.people

import app.logdate.client.repository.knowledge.ContactImportSummary
import app.logdate.client.repository.knowledge.DeviceContact
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OfflineFirstPeopleRepositoryContactImportTest {
    private val daos = FakePeopleDaos()
    private val patel = DeviceContact(lookupKey = "patel-key", displayName = "Dr. Patel", aliases = listOf("Doc"))
    private val ava = DeviceContact(lookupKey = "ava-key", displayName = "Ava")

    @Test
    fun `importing contacts twice updates them instead of adding them again`() =
        runTest {
            val repository = repository()

            val first = repository.importContacts(listOf(patel, ava), PersonOrigin.CONTACT_FULL)
            val second = repository.importContacts(listOf(patel, ava), PersonOrigin.CONTACT_FULL)

            assertEquals(ContactImportSummary(importedCount = 2, updatedCount = 0), first)
            assertEquals(ContactImportSummary(importedCount = 0, updatedCount = 2), second)
            assertEquals(listOf("Dr. Patel", "Ava"), listedNames())
        }

    @Test
    fun `a contact removed from the directory is not brought back by a later import`() =
        runTest {
            val repository = repository()
            repository.importContacts(listOf(patel, ava), PersonOrigin.CONTACT_FULL)
            val removed = daos.people.values.single { it.name == "Dr. Patel" }
            daos.personDao.softDelete(removed.id, Instant.parse("2026-03-02T09:00:00Z"))

            val summary = repository.importContacts(listOf(patel, ava), PersonOrigin.CONTACT_FULL)

            assertEquals(ContactImportSummary(importedCount = 0, updatedCount = 1), summary)
            assertEquals(listOf("Ava"), listedNames())
            val patelRow = daos.people.values.single { it.contactLookupKey == "patel-key" }
            assertEquals(removed.id, patelRow.id)
        }

    private fun TestScope.repository() = OfflineFirstPeopleRepository(daos.personDao, dispatcher = StandardTestDispatcher(testScheduler))

    private suspend fun listedNames(): List<String> = daos.personDao.getAll().map { it.name }
}
