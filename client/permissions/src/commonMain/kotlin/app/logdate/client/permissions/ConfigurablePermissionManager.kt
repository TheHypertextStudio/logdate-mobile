package app.logdate.client.permissions

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configurable [PermissionManager] for platforms without runtime permission prompts and tests.
 *
 * By default, this implementation reports all permissions as granted,
 * but tests can configure denied permissions.
 */
open class ConfigurablePermissionManager(
    // Set this to false to return denied permissions in tests.
    private val alwaysGrantPermissions: Boolean = true,
    // Use this map to override specific permissions' behavior
    private val permissionOverrides: Map<PermissionType, Boolean> = emptyMap(),
) : PermissionManager {
    private val permissionStatusMap = MutableStateFlow<Map<PermissionType, PermissionStatus>>(emptyMap())

    override fun isPermissionGranted(type: PermissionType): Boolean = permissionOverrides[type] ?: alwaysGrantPermissions

    override fun arePermissionsGranted(types: Set<PermissionType>): Boolean = types.all { isPermissionGranted(it) }

    override fun observePermissions(types: Set<PermissionType>): StateFlow<Map<PermissionType, PermissionStatus>> {
        val statusMap =
            types.associateWith {
                val granted = isPermissionGranted(it)
                if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
            }

        permissionStatusMap.value = statusMap
        return permissionStatusMap.asStateFlow()
    }

    override fun requestPermission(
        type: PermissionType,
        onResult: (PermissionResult) -> Unit,
    ) {
        val granted = isPermissionGranted(type)
        val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED

        Napier.d("Configurable permission manager ${if (granted) "granted" else "denied"} permission: $type")
        onResult(PermissionResult(type, status))
    }

    override fun requestPermissions(
        types: Set<PermissionType>,
        onResult: (List<PermissionResult>) -> Unit,
    ) {
        val results =
            types.map {
                val granted = isPermissionGranted(it)
                val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
                PermissionResult(it, status)
            }

        Napier.d("Configurable permission manager processed permission request: $types")
        onResult(results)
    }

    override fun openAppSettings() {
        Napier.d("Configurable permission manager attempted to open app settings")
    }

    override fun openPermissionSettings() {
        Napier.d("Configurable permission manager attempted to open permission settings")
    }

    override fun shouldShowRationale(type: PermissionType): Boolean {
        // Return true if permission is denied and not in overrides
        // for first-denial flows where rationale should be shown.
        return !alwaysGrantPermissions && type !in permissionOverrides
    }
}
