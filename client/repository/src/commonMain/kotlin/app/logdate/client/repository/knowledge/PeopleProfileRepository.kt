package app.logdate.client.repository.knowledge

import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Event
import app.logdate.shared.model.Person
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

data class PersonRelatedContent(
    val person: Person,
    val linkedEntries: List<JournalNote>,
    val linkedEvents: List<Event>,
)

interface PeopleProfileRepository {
    fun observePerson(personId: Uuid): Flow<Person?>

    fun observeProfile(personId: Uuid): Flow<PersonRelatedContent?>

    /**
     * Removes a person from the directory and records that neither their name nor any of their
     * other names is suggested again. A contact removed this way is not added again by a later
     * contact import.
     *
     * Removing a person who is no longer in the directory does nothing. Storage failures are thrown.
     */
    suspend fun removePerson(personId: Uuid)
}
