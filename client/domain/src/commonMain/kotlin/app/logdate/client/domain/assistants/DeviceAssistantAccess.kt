package app.logdate.client.domain.assistants

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.StoredDeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * What assistants on this device, such as Android assistants, may see and do in LogDate. Nothing is
 * allowed until the person turns it on, and nothing here lets an assistant write or change an entry.
 */
data class DeviceAssistantAccess(
    val capabilities: Set<AssistantCapability> = emptySet(),
    val contentScope: AssistantContentScope = AssistantContentScope(emptySet()),
) {
    /** Whether the person allows any of [needed]. */
    fun allowsAny(needed: Set<AssistantCapability>): Boolean = capabilities.any { it in needed }

    override fun toString(): String = "DeviceAssistantAccess([redacted])"
}

/** Keeps [DeviceAssistantAccess] in this device's preferences. */
class DeviceAssistantAccessStore(
    private val preferences: LogdatePreferencesDataSource,
) {
    fun observe(): Flow<DeviceAssistantAccess> = preferences.observeDeviceAssistantAccess().map { it.toAccess() }

    suspend fun current(): DeviceAssistantAccess = observe().first()

    suspend fun set(access: DeviceAssistantAccess) {
        preferences.setDeviceAssistantAccess(access.toStored())
    }
}

private val capabilityScopes = AssistantCapability.serializer().descriptor

private val AssistantCapability.scope: String get() = capabilityScopes.getElementName(ordinal)

private fun capabilityForScope(scope: String): AssistantCapability? =
    capabilityScopes.getElementIndex(scope).takeIf { it >= 0 }?.let(AssistantCapability.entries::get)

private fun StoredDeviceAssistantAccess.toAccess(): DeviceAssistantAccess =
    DeviceAssistantAccess(
        capabilities = scopes.mapNotNull(::capabilityForScope).toSet(),
        contentScope =
            AssistantContentScope(
                journalIds = journalIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet(),
                includeUnfiled = includesUnfiled,
            ),
    )

private fun DeviceAssistantAccess.toStored(): StoredDeviceAssistantAccess =
    StoredDeviceAssistantAccess(
        scopes = capabilities.map { it.scope }.toSet(),
        journalIds = contentScope.journalIds.map { it.toString() }.toSet(),
        includesUnfiled = contentScope.includeUnfiled,
    )
