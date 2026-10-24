package app.logdate.feature.postcards.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.PostcardDocument
import app.logdate.ui.adaptive.HingeAwareOverlay
import logdate.client.feature.postcards.generated.resources.Res
import logdate.client.feature.postcards.generated.resources.postcards_viewer_load_error
import logdate.client.ui.generated.resources.common_back
import logdate.client.ui.generated.resources.common_edit
import logdate.client.ui.generated.resources.common_share
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Full-screen viewer for a saved Postcard.
 *
 * Renders the Postcard with ambient parallax and allows two-finger pan/zoom.
 * Photo elements are tappable to navigate to the source moment (intertextuality).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardViewerScreen(
    viewModel: PostcardViewerViewModel = koinViewModel(),
    exportViewModel: ExportViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = {},
    onEditPostcard: (Uuid) -> Unit = {},
    onShareUri: (String) -> Unit = {},
    onSaveToFiles: ((String) -> Unit)? = null,
    onPrint: ((String) -> Unit)? = null,
    onNavigateToMoment: (Uuid) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var showExportSheet by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    val title = (uiState as? PostcardViewerUiState.Loaded)?.document?.title ?: ""
                    Text(title)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiRes.string.common_back))
                    }
                },
                actions = {
                    val loaded = uiState as? PostcardViewerUiState.Loaded
                    if (loaded != null) {
                        IconButton(onClick = { onEditPostcard(loaded.document.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(UiRes.string.common_edit))
                        }
                        IconButton(onClick = {
                            exportViewModel.startExport()
                            showExportSheet = true
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(UiRes.string.common_share))
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is PostcardViewerUiState.Loading -> {
                    CircularProgressIndicator()
                }

                is PostcardViewerUiState.Loaded -> {
                    // The Postcard is a single immersive canvas, so on a folded device it should
                    // live wholly inside one physical pane rather than straddle a separating hinge.
                    HingeAwareOverlay {
                        PostcardViewerContent(
                            document = state.document,
                            stickerUriMap = state.stickerUriMap,
                            onPhotoTap = { photo ->
                                onNavigateToMoment(photo.momentRef)
                            },
                        )
                    }

                    if (showExportSheet) {
                        ExportSheet(
                            document = state.document,
                            viewModel = exportViewModel,
                            stickerUriMap = state.stickerUriMap,
                            onShareResult = { uri ->
                                showExportSheet = false
                                onShareUri(uri)
                            },
                            onSaveToFiles =
                                onSaveToFiles?.let { save ->
                                    { uri ->
                                        showExportSheet = false
                                        save(uri)
                                    }
                                },
                            onPrint =
                                onPrint?.let { print ->
                                    { uri ->
                                        showExportSheet = false
                                        print(uri)
                                    }
                                },
                            onDismiss = { showExportSheet = false },
                        )
                    }
                }

                is PostcardViewerUiState.Error -> {
                    Text(stringResource(Res.string.postcards_viewer_load_error))
                }
            }
        }
    }
}

@Composable
fun PostcardViewerContent(
    document: PostcardDocument,
    stickerUriMap: Map<Uuid, String>,
    onPhotoTap: (CanvasElement.Photo) -> Unit,
) {
    val viewportState = rememberCanvasViewportState()

    CanvasViewport(
        state = viewportState,
        modifier = Modifier.fillMaxSize(),
    ) {
        CanvasRenderer(
            document = document,
            stickerUriMap = stickerUriMap,
            viewportOffsetX = viewportState.offset.x,
            viewportOffsetY = viewportState.offset.y,
            onPhotoTap = onPhotoTap,
        )
    }
}
