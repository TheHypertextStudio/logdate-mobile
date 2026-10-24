package app.logdate.client.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android implementation of the PermissionManager interface.
 *
 * Handles permission checks, requests, and statuses for Android platform.
 */
class AndroidPermissionManager(
    private val context: Context,
) : PermissionManager {
    private val permissionStatusMap = MutableStateFlow<Map<PermissionType, PermissionStatus>>(emptyMap())

    // Map PermissionType to Android permissions
    private fun getAndroidPermissions(type: PermissionType): Array<String> =
        when (type) {
            PermissionType.LOCATION -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            }

            PermissionType.CAMERA -> {
                arrayOf(
                    Manifest.permission.CAMERA,
                )
            }

            PermissionType.MICROPHONE -> {
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                )
            }

            PermissionType.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+: include READ_MEDIA_VISUAL_USER_SELECTED so the
                    // system serves the three-way photo-picker prompt. The
                    // granted check below treats any of {IMAGES, VIDEO,
                    // USER_SELECTED} as visual access granted.
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    arrayOf() // Use MediaStore API instead for Android 11+
                } else {
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                }
            }

            PermissionType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    arrayOf() // Not needed before Android 13
                }
            }

            PermissionType.CALENDAR -> {
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                )
            }

            PermissionType.CONTACTS -> {
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                )
            }

            PermissionType.BIOMETRIC -> {
                arrayOf(
                    Manifest.permission.USE_BIOMETRIC,
                )
            }

            PermissionType.ACTIVITY_RECOGNITION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
                } else {
                    arrayOf()
                }
            }
        }

    override fun isPermissionGranted(type: PermissionType): Boolean {
        val permissions = getAndroidPermissions(type)

        // If no permissions are needed (like notifications pre-Android 13), return true
        if (permissions.isEmpty()) {
            return true
        }

        val isGranted = { p: String ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        // Location is disjunctive: either fine or coarse is sufficient.
        // Storage on Android 14+ is also disjunctive: partial media access
        // (READ_MEDIA_VISUAL_USER_SELECTED) counts as granted even when the
        // user denied READ_MEDIA_IMAGES.
        return when (type) {
            PermissionType.LOCATION -> {
                permissions.any(isGranted)
            }

            PermissionType.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissions.any(isGranted)
                } else {
                    permissions.all(isGranted)
                }
            }

            else -> {
                permissions.all(isGranted)
            }
        }
    }

    override fun arePermissionsGranted(types: Set<PermissionType>): Boolean = types.all { isPermissionGranted(it) }

    override fun observePermissions(types: Set<PermissionType>): StateFlow<Map<PermissionType, PermissionStatus>> {
        // Update the status map for the requested types
        val statusMap = types.associateWith { getPermissionStatus(it) }
        permissionStatusMap.update { it + statusMap }

        return permissionStatusMap.asStateFlow()
    }

    /**
     * Get the current status of a permission
     */
    private fun getPermissionStatus(type: PermissionType): PermissionStatus {
        val permissions = getAndroidPermissions(type)

        // If no permissions are needed, return granted
        if (permissions.isEmpty()) {
            return PermissionStatus.GRANTED
        }

        val isGranted = { p: String ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        val granted =
            when (type) {
                PermissionType.LOCATION -> {
                    permissions.any(isGranted)
                }

                PermissionType.STORAGE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        permissions.any(isGranted)
                    } else {
                        permissions.all(isGranted)
                    }
                }

                else -> {
                    permissions.all(isGranted)
                }
            }

        return if (granted) {
            PermissionStatus.GRANTED
        } else {
            // We can't determine if permanently denied without an activity
            PermissionStatus.DENIED
        }
    }

    override fun requestPermission(
        type: PermissionType,
        onResult: (PermissionResult) -> Unit,
    ) {
        // This is a capability fallback since we can't directly request permissions from a non-activity context
        // In real usage, this would be implemented in the activity/fragment
        Napier.w("Cannot request permission directly from AndroidPermissionManager. Use ActivityResultContracts in your Activity/Fragment.")

        val status = getPermissionStatus(type)
        onResult(PermissionResult(type, status))
    }

    override fun requestPermissions(
        types: Set<PermissionType>,
        onResult: (List<PermissionResult>) -> Unit,
    ) {
        // This is a capability fallback since we can't directly request permissions from a non-activity context
        Napier.w(
            "Cannot request permissions directly from AndroidPermissionManager. Use ActivityResultContracts in your Activity/Fragment.",
        )

        val results =
            types.map {
                val status = getPermissionStatus(it)
                PermissionResult(it, status)
            }
        onResult(results)
    }

    override fun openAppSettings() {
        try {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        } catch (e: Exception) {
            Napier.e("Failed to open app settings", e)
        }
    }

    override fun openPermissionSettings() {
        // No public API exists for opening the per-app permissions screen directly.
        // Fall back to the app details settings screen which includes permissions.
        openAppSettings()
    }

    override fun shouldShowRationale(type: PermissionType): Boolean {
        // This can only be determined from an Activity context
        // We'll always return false here
        return false
    }
}
