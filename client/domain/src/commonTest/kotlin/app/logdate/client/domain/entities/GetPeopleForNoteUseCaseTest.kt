package app.logdate.client.domain.entities

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.AIUnavailableReason
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheEntry
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.entity.people.PeopleExtractor
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponse
import app.logdate.client.networking.DataRestriction
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.shared.model.Person
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Tests for [GetPeopleForNoteUseCase], which is responsible for identifying people
 * associated with a specific note.
 *
 * Note: The current implementation is a placeholder that validates the expected
 * [NotImplementedError] behavior across various input scenarios.
 */
class GetPeopleForNoteUseCaseTest {
    private val existingPerson = Person(name = "Jane Doe")
    private val repository = FakePeopleRepository(listOf(existingPerson))

    @Test
    fun `invoke resolves extracted people against repository`() =
        runTest {
            val useCase =
                GetPeopleForNoteUseCase(
                    extractPeopleUseCase =
                        ExtractPeopleUseCase(
                            peopleExtractor =
                                peopleExtractor(
                                    AIResult.Success(
                                        GenerativeAIResponse(
                                            content = """{"names":["Jane Doe","Sam Lee"]}""",
                                        ),
                                    ),
                                ),
                        ),
                    peopleRepository = repository,
                )

            val result = useCase(noteId = "note-1", text = "Dinner with Jane Doe and Sam Lee")

            assertEquals(existingPerson, result[0])
            assertEquals("Sam Lee", result[1].name)
        }

    @Test
    fun `invoke returns empty list when extraction is unavailable`() =
        runTest {
            val useCase =
                GetPeopleForNoteUseCase(
                    extractPeopleUseCase =
                        ExtractPeopleUseCase(
                            peopleExtractor =
                                peopleExtractor(
                                    AIResult.Unavailable(AIUnavailableReason.MissingCredentials),
                                ),
                        ),
                    peopleRepository = repository,
                )

            assertEquals(emptyList(), useCase(noteId = "note-1", text = "Dinner with Jane Doe"))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun peopleExtractor(result: AIResult<GenerativeAIResponse>) =
        PeopleExtractor(
            generativeAICache = NoopGenerativeAICache,
            generativeAIChatClient = FakeGenerativeAIChatClient(result),
            networkAvailabilityMonitor = AlwaysConnectedNetworkMonitor,
            dataUsagePolicy = UnrestrictedDataUsagePolicy,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
}

private object NoopGenerativeAICache : GenerativeAICache {
    override suspend fun getEntry(request: GenerativeAICacheRequest): GenerativeAICacheEntry? = null

    override suspend fun putEntry(
        request: GenerativeAICacheRequest,
        content: String,
    ) = Unit

    override suspend fun purge() = Unit
}

private class FakeGenerativeAIChatClient(
    private val result: AIResult<GenerativeAIResponse>,
) : GenerativeAIChatClient {
    override val providerId: String = "test"
    override val defaultModel: String = "test-model"

    override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> = result
}

private object AlwaysConnectedNetworkMonitor : NetworkAvailabilityMonitor {
    override fun isNetworkAvailable(): Boolean = true

    override fun observeNetwork(): MutableSharedFlow<NetworkState> = MutableSharedFlow()
}

private object UnrestrictedDataUsagePolicy : DataUsagePolicy {
    override val policy: Flow<DataUsageMode> = flowOf(DataUsageMode.Unrestricted)

    override suspend fun currentMode(): DataUsageMode = DataUsageMode.Unrestricted

    override suspend fun currentRestriction(): DataRestriction = DataRestriction.NONE
}

private class FakePeopleRepository(
    people: List<Person>,
) : PeopleRepository {
    private val peopleById = people.associateBy { it.uid }.toMutableMap()

    override suspend fun getPerson(uid: Uuid): Person = peopleById.getValue(uid)

    override fun getAllPeople(): Flow<List<Person>> = flowOf(peopleById.values.toList())

    override suspend fun resolvePersonByName(name: String): Person? = peopleById.values.firstOrNull { it.name == name }

    override suspend fun resolvePersonByDescription(description: String): Person? = null

    override suspend fun addPerson(person: Person) {
        peopleById[person.uid] = person
    }

    override suspend fun updatePerson(person: Person) {
        peopleById[person.uid] = person
    }

    override suspend fun deletePerson(uid: Uuid) {
        peopleById.remove(uid)
    }

    override suspend fun addAliasToPerson(
        personUid: Uuid,
        alias: String,
    ) = Unit

    override suspend fun removeAliasFromPerson(
        personUid: Uuid,
        alias: String,
    ) = Unit
}
