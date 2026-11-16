package app.logdate.client.domain.assistants

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.StoredDeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DeviceAssistantAccessStoreTest {
    private val preferences = LogdatePreferencesDataSource(MemoryPreferences())
    private val store = DeviceAssistantAccessStore(preferences)
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")

    @Test
    fun `nothing is allowed until the person turns it on`() =
        runTest {
            val access = store.current()
            assertTrue(access.capabilities.isEmpty())
            assertTrue(access.contentScope.journalIds.isEmpty())
            assertFalse(access.contentScope.includeUnfiled)
        }

    @Test
    fun `what the person allows is kept`() =
        runTest {
            val allowed =
                DeviceAssistantAccess(
                    capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.START_CAPTURE),
                    contentScope = AssistantContentScope(setOf(travel), includeUnfiled = true),
                )
            store.set(allowed)
            assertEquals(allowed, store.current())
            assertEquals(setOf("memories:read", "capture:start"), preferences.observeDeviceAssistantAccess().first().scopes)
        }

    @Test
    fun `unknown permissions and malformed journal ids are ignored`() =
        runTest {
            preferences.setDeviceAssistantAccess(
                StoredDeviceAssistantAccess(
                    scopes = setOf("entries:add", "places:read"),
                    journalIds = setOf("not-a-uuid", travel.toString()),
                ),
            )
            val access = store.current()
            assertEquals(setOf(AssistantCapability.READ_PLACES), access.capabilities)
            assertEquals(setOf(travel), access.contentScope.journalIds)
        }
}

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
