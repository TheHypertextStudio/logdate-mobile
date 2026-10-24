package app.logdate.client.domain.entities

import app.logdate.client.intelligence.AIResult
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.shared.model.Person

/**
 * A use case to get all people mentioned in a note.
 */
class GetPeopleForNoteUseCase(
    private val extractPeopleUseCase: ExtractPeopleUseCase,
    private val peopleRepository: PeopleRepository,
) {
    suspend operator fun invoke(
        noteId: String,
        text: String,
    ): List<Person> =
        when (val result = extractPeopleUseCase(noteId, text)) {
            is AIResult.Success -> {
                result.value.map { extractedPerson ->
                    peopleRepository.resolvePersonByName(extractedPerson.name) ?: extractedPerson
                }
            }

            is AIResult.Error,
            is AIResult.Unavailable,
            -> {
                emptyList()
            }
        }
}
