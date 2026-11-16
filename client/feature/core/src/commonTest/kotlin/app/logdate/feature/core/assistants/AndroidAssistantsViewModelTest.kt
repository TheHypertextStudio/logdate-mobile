package app.logdate.feature.core.assistants

import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.shared.model.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAssistantsViewModelTest {
    private val travel = Journal(id = Uuid.parse("00000000-0000-4000-8000-000000000001"), title = "Travel")
    private val saved = MutableStateFlow(DeviceAssistantAccess())

    // Built after setUp installs the test dispatcher, which viewModelScope captures.
    private val model by lazy {
        AndroidAssistantsViewModel(
            access = saved,
            saveAccess = { saved.value = it },
            journals = MutableStateFlow(listOf(travel)),
        )
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `everything starts off, with the person's journals to choose from`() =
        runTest {
            val state = model.state.filterNotNull().first()
            assertEquals(listOf(AssistantJournalOption(travel.id, "Travel")), state.journals)
            assertEquals(AssistantAccessSelection(), state.access)
        }

    @Test
    fun `what the person turns on is saved and shown`() =
        runTest {
            model.state.filterNotNull().first()
            model.setAccess(
                AssistantAccessSelection(
                    capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.START_CAPTURE),
                    journalIds = setOf(travel.id),
                ),
            )
            assertEquals(
                DeviceAssistantAccess(
                    capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.START_CAPTURE),
                    contentScope = AssistantContentScope(setOf(travel.id)),
                ),
                saved.value,
            )
            val shown = model.state.filterNotNull().first()
            assertEquals(setOf(travel.id), shown.access.journalIds)
        }
}
