package app.logdate.feature.editor.ui.image

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.logdate.client.media.AndroidManagedMediaDiscarder
import app.logdate.client.media.AndroidManagedMediaImportSource
import app.logdate.client.media.ManagedMediaImporter
import app.logdate.client.media.ManagedMediaPublicationCustody
import app.logdate.client.media.MediaManager
import app.logdate.client.permissions.PermissionManager
import app.logdate.feature.editor.ui.common.findActivity
import app.logdate.feature.editor.ui.media.ManagedMediaSelectionController
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Suppress("ktlint:standard:function-naming")
@Composable
actual fun ImagePickerContent(
    onImageSelected: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mediaManager: MediaManager = koinInject()
    val publicationCustody: ManagedMediaPublicationCustody = koinInject()
    val permissionManager: PermissionManager = koinInject()
    val imagePermissions = remember { imageLibraryPermissions() }
    val coroutineScope = rememberCoroutineScope()
    val currentOnImageSelected by rememberUpdatedState(onImageSelected)
    val managedMediaDiscarder =
        remember(context, mediaManager) { AndroidManagedMediaDiscarder(context.applicationContext, mediaManager) }
    val managedMediaImporter =
        remember(context, mediaManager, managedMediaDiscarder, publicationCustody) {
            ManagedMediaImporter(
                mediaManager = mediaManager,
                source = AndroidManagedMediaImportSource(context.applicationContext),
                discardManagedMedia = managedMediaDiscarder::discard,
                publicationCustody = publicationCustody,
            )
        }
    val selectionController =
        remember(managedMediaImporter, managedMediaDiscarder) {
            ManagedMediaSelectionController(
                importMedia = managedMediaImporter::import,
                discardManagedMedia = managedMediaDiscarder::discard,
            )
        }
    val importState by selectionController.state.collectAsState()

    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var libraryState by remember { mutableStateOf<ImagePickerLibraryState>(ImagePickerLibraryState.Loading) }

    fun importSelection(sourceUri: String) {
        coroutineScope.launch {
            selectionController.selectPreparedAndTransfer(
                sourceUri = sourceUri,
                prepareManagedMedia = { it },
                transferOwnership = currentOnImageSelected,
            )
        }
    }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri == null) {
                Napier.d("Android image picker dismissed without a selection")
                coroutineScope.launch { selectionController.cancel() }
            } else {
                Napier.d("Android image selected from manual picker")
                importSelection(uri.toString())
            }
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            hasRequestedPermission = true
            reloadTrigger++
        }

    fun hasAnyImagePermission(): Boolean =
        imagePermissions.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun hasFullImageAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun permissionRequiredState(): ImagePickerLibraryState.PermissionRequired {
        // Use the primary permission for rationale detection; if the system
        // won't show rationale for it, the user has dismissed the prompt
        // enough times that we should route them to Settings instead.
        val rationalePermission = imagePermissions.first()
        val permanentlyDenied =
            hasRequestedPermission &&
                context.findActivity()?.let { activity ->
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, rationalePermission)
                } == true
        return ImagePickerLibraryState.PermissionRequired(permanentlyDenied = permanentlyDenied)
    }

    // Long-lived collection: emits the initial snapshot and then re-emits a
    // fresh list every time MediaStore reports a change, so a photo captured
    // in the system camera appears in this grid without a manual refresh.
    LaunchedEffect(imagePermissions, reloadTrigger) {
        if (!hasAnyImagePermission()) {
            libraryState = permissionRequiredState()
            return@LaunchedEffect
        }
        libraryState = ImagePickerLibraryState.Loading
        try {
            // When the user grants partial access on Android 14+, MediaStore
            // transparently filters its results to the selected items — no
            // extra work needed here.
            // Ask for ~4x the preview cap so the cursor still returns enough
            // images after the videos-only filter strips out clips.
            mediaManager.getRecentMedia(limit = RECENT_IMAGE_PREVIEW_LIMIT * 4).collect { mediaList ->
                val images = recentImagePreviews(mediaList)
                libraryState =
                    when {
                        images.isEmpty() -> ImagePickerLibraryState.Empty

                        // On Android 14+, "Select photos" grants the user-selected
                        // permission but denies READ_MEDIA_IMAGES; surface a
                        // distinct state so the picker can show the Manage pill.
                        !hasFullImageAccess() -> ImagePickerLibraryState.PartialAccess(images)

                        else -> ImagePickerLibraryState.Loaded(images)
                    }
            }
        } catch (error: SecurityException) {
            Napier.w("Recent image access requires permission", error)
            libraryState = permissionRequiredState()
        } catch (error: Exception) {
            Napier.e("Failed to load recent Android images", error)
            libraryState = ImagePickerLibraryState.Error
        }
    }

    // On returning from system settings, re-check permission status — the
    // reactive flow above doesn't get notified about permission changes, only
    // content changes. Bumping the trigger re-runs the LaunchedEffect.
    DisposableEffect(lifecycleOwner, imagePermissions, hasRequestedPermission) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    reloadTrigger++
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ImagePickerBrowser(
        state = libraryState,
        importState = importState,
        onImageSelected = ::importSelection,
        onBrowseLibrary = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRequestLibraryAccess = {
            hasRequestedPermission = true
            permissionLauncher.launch(imagePermissions)
        },
        onManageSelection = {
            // Re-running the permission request on Android 14+ shows the same
            // three-way prompt the user originally answered, which is what
            // the system documents as the "Manage selection" entry point.
            hasRequestedPermission = true
            permissionLauncher.launch(imagePermissions)
        },
        onOpenSettings = { permissionManager.openPermissionSettings() },
        onRetryLoading = { reloadTrigger++ },
        onRetryImport = {
            coroutineScope.launch {
                selectionController.retryPreparedAndTransfer(
                    prepareManagedMedia = { it },
                    transferOwnership = currentOnImageSelected,
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * The set of media-library permissions to request together.
 *
 * On Android 14+, requesting `READ_MEDIA_VISUAL_USER_SELECTED` alongside
 * `READ_MEDIA_IMAGES` opts the app into the three-way system prompt
 * (All photos / Select photos / Don't allow). When the user picks "Select",
 * MediaStore transparently scopes results to the selected items, so the
 * recent-photos grid below works without further changes.
 */
private fun imageLibraryPermissions(): Array<String> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }

        else -> {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
