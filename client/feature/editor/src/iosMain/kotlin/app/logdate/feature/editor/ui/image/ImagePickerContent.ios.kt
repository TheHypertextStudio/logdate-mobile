package app.logdate.feature.editor.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.media.IosMediaManager
import app.logdate.client.media.MediaManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.koinInject
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCurrent
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

@Suppress("ktlint:standard:function-naming")
@Composable
actual fun ImagePickerContent(
    onImageSelected: (String) -> Unit,
    modifier: Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val mediaManager: MediaManager = koinInject()
    val iosMediaManager = mediaManager as? IosMediaManager

    var activeDelegate by remember { mutableStateOf<PhotoLibraryPickerDelegate?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var libraryState by remember { mutableStateOf<ImagePickerLibraryState>(ImagePickerLibraryState.Loading) }

    fun refreshRecentImages() {
        val status = currentPhotoLibraryStatus()
        if (!status.canReadPhotoLibrary()) {
            libraryState = status.toImagePickerState()
            return
        }

        coroutineScope.launch {
            libraryState = ImagePickerLibraryState.Loading

            libraryState =
                try {
                    val images = recentImagePreviews(mediaManager.getRecentMedia().first())
                    if (images.isEmpty()) {
                        ImagePickerLibraryState.Empty
                    } else {
                        ImagePickerLibraryState.Loaded(images)
                    }
                } catch (error: Exception) {
                    Napier.e("Failed to load recent iOS photos", error)
                    ImagePickerLibraryState.Error
                }
        }
    }

    LaunchedEffect(reloadTrigger) {
        refreshRecentImages()
    }

    ImagePickerBrowser(
        state = libraryState,
        onImageSelected = onImageSelected,
        onBrowseLibrary = {
            val presentingController = topViewController()
            if (presentingController == null) {
                Napier.w("Unable to present iOS photo library picker because no root view controller was found")
                return@ImagePickerBrowser
            }

            val pickerDelegate =
                PhotoLibraryPickerDelegate(
                    onAssetSelected = { localIdentifier ->
                        coroutineScope.launch {
                            val resolvedUri =
                                iosMediaManager?.resolvePhotoLibraryImageUri(localIdentifier)
                                    ?: run {
                                        Napier.w("Unable to resolve renderable URI for iOS photo-library asset $localIdentifier")
                                        null
                                    }

                            activeDelegate = null

                            if (resolvedUri != null) {
                                onImageSelected(resolvedUri)
                            }
                        }
                    },
                    onDismiss = {
                        activeDelegate = null
                    },
                )

            activeDelegate = pickerDelegate
            presentPhotoPicker(
                presentingController = presentingController,
                delegate = pickerDelegate,
            )
        },
        onRequestLibraryAccess = {
            coroutineScope.launch {
                requestPhotoLibraryAuthorization()
                reloadTrigger++
            }
        },
        onOpenSettings = { openIosAppSettings() },
        onRetryLoading = { reloadTrigger++ },
        modifier = modifier,
    )
}

private fun currentPhotoLibraryStatus(): PHAuthorizationStatus = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)

private fun PHAuthorizationStatus.canReadPhotoLibrary(): Boolean =
    this == PHAuthorizationStatusAuthorized ||
        this == PHAuthorizationStatusLimited

private fun PHAuthorizationStatus.toImagePickerState(): ImagePickerLibraryState =
    when (this) {
        PHAuthorizationStatusAuthorized,
        PHAuthorizationStatusLimited,
        -> ImagePickerLibraryState.Loading

        PHAuthorizationStatusDenied,
        PHAuthorizationStatusRestricted,
        -> ImagePickerLibraryState.PermissionRequired(permanentlyDenied = true)

        PHAuthorizationStatusNotDetermined -> ImagePickerLibraryState.PermissionRequired(permanentlyDenied = false)

        else -> ImagePickerLibraryState.PermissionRequired(permanentlyDenied = false)
    }

private suspend fun requestPhotoLibraryAuthorization(): PHAuthorizationStatus =
    suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
            if (continuation.isActive) {
                continuation.resume(status)
            }
        }
    }

private fun presentPhotoPicker(
    presentingController: UIViewController,
    delegate: PhotoLibraryPickerDelegate,
) {
    val configuration =
        PHPickerConfiguration(photoLibrary = PHPhotoLibrary.sharedPhotoLibrary()).apply {
            selectionLimit = 1
            filter = PHPickerFilter.imagesFilter()
            preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent
        }

    val picker = PHPickerViewController(configuration = configuration)
    picker.delegate = delegate

    presentingController.presentViewController(
        viewControllerToPresent = picker,
        animated = true,
        completion = null,
    )
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (controller.presentedViewController != null) {
        controller = controller.presentedViewController!!
    }
    return controller
}

private fun openIosAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url)
}

private class PhotoLibraryPickerDelegate(
    private val onAssetSelected: (String) -> Unit,
    private val onDismiss: () -> Unit,
) : NSObject(),
    PHPickerViewControllerDelegateProtocol {
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        val assetIdentifier = result?.assetIdentifier

        picker.dismissViewControllerAnimated(true, completion = null)

        if (assetIdentifier.isNullOrBlank()) {
            Napier.d("iOS photo picker dismissed without a local asset identifier")
            onDismiss()
            return
        }

        Napier.d("iOS photo library image selected: $assetIdentifier")
        onAssetSelected(assetIdentifier)
    }
}
