@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.journals.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.feature.journals.ui.JournalShape
import app.logdate.feature.journals.ui.deriveCoverColor
import app.logdate.shared.model.Journal
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.AspectRatios
import app.logdate.ui.common.applyStandardContentWidth
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.*
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.ui.generated.resources.common_go_back
import logdate.client.ui.generated.resources.common_loading
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Screen for sharing a journal with others.
 *
 * Always offers the journal cover as an Instagram story. A link and QR code are offered only when
 * the connected server hosts shared journals, because otherwise a link reaches no one.
 *
 * @param journalId ID of the journal to share
 * @param onGoBack Callback to navigate back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareJournalScreen(
    journalId: String,
    onGoBack: () -> Unit,
) {
    val viewModel = koinInject<ShareJournalViewModel>()
    val parsedId =
        remember(journalId) {
            kotlin.uuid.Uuid.parse(journalId)
        }

    LaunchedEffect(parsedId) {
        viewModel.setJournalId(parsedId)
    }

    val uiState by viewModel.uiState.collectAsState()

    ShareJournalScreenContent(
        uiState = uiState,
        onGoBack = onGoBack,
        onShareToInstagram = { journal -> viewModel.shareToInstagram(journal) },
        onShareQrCode = { journal -> viewModel.shareJournalQrCode(journal) },
        onShareJournal = { journal -> viewModel.shareJournal(journal) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareJournalScreenContent(
    uiState: ShareJournalUiState,
    onGoBack: () -> Unit,
    onShareToInstagram: (Journal) -> Unit,
    onShareQrCode: (Journal) -> Unit,
    onShareJournal: (Journal) -> Unit,
    modifier: Modifier = Modifier,
    previewCoverPainter: Painter? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.journal_share_label)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(UiRes.string.common_go_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is ShareJournalUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(UiRes.string.common_loading))
                }
            }

            is ShareJournalUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.failed_to_load_journal))
                }
            }

            is ShareJournalUiState.Success -> {
                ShareJournalContent(
                    journal = state.journal,
                    linkSharingAvailable = state.linkSharingAvailable,
                    onShareToInstagram = { onShareToInstagram(state.journal) },
                    onShareQrCode = { onShareQrCode(state.journal) },
                    onShareJournal = { onShareJournal(state.journal) },
                    modifier = Modifier.padding(paddingValues),
                    previewCoverPainter = previewCoverPainter,
                )
            }
        }
    }
}

/**
 * Content of the share journal screen.
 *
 * Displays a share-specific journal card alongside sharing actions.
 *
 * @param journal Journal to be shared
 * @param linkSharingAvailable Whether a journal link can reach someone else
 * @param onShareToInstagram Callback when sharing to Instagram
 * @param onShareQrCode Callback when sharing a QR code
 * @param onShareJournal Callback when using general share sheet
 * @param modifier Modifier for this composable
 */
@Composable
fun ShareJournalContent(
    journal: Journal,
    linkSharingAvailable: Boolean,
    onShareToInstagram: () -> Unit,
    onShareQrCode: () -> Unit,
    onShareJournal: () -> Unit,
    modifier: Modifier = Modifier,
    previewCoverPainter: Painter? = null,
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            ShareJournalPrimaryPane(
                journal = journal,
                modifier = Modifier.fillMaxSize(),
                previewCoverPainter = previewCoverPainter,
            )
        },
        endPane = {
            ShareJournalActionPane(
                linkSharingAvailable = linkSharingAvailable,
                onShareToInstagram = onShareToInstagram,
                onShareQrCode = onShareQrCode,
                onShareJournal = onShareJournal,
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            ShareJournalStandardContent(
                journal = journal,
                linkSharingAvailable = linkSharingAvailable,
                onShareToInstagram = onShareToInstagram,
                onShareQrCode = onShareQrCode,
                onShareJournal = onShareJournal,
                modifier = Modifier.fillMaxSize(),
                previewCoverPainter = previewCoverPainter,
            )
        },
    )
}

@Composable
private fun ShareJournalStandardContent(
    journal: Journal,
    linkSharingAvailable: Boolean,
    onShareToInstagram: () -> Unit,
    onShareQrCode: () -> Unit,
    onShareJournal: () -> Unit,
    modifier: Modifier = Modifier,
    previewCoverPainter: Painter? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= 840.dp || (maxWidth >= 700.dp && maxWidth > maxHeight)) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShareJournalPrimaryPane(
                    journal = journal,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    previewCoverPainter = previewCoverPainter,
                )
                ShareJournalActionPane(
                    linkSharingAvailable = linkSharingAvailable,
                    onShareToInstagram = onShareToInstagram,
                    onShareQrCode = onShareQrCode,
                    onShareJournal = onShareJournal,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.lg)
                        .applyStandardContentWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                ShareJournalCard(
                    journal = journal,
                    modifier = Modifier.widthIn(max = 240.dp),
                    previewCoverPainter = previewCoverPainter,
                )

                ShareJournalDescription(linkSharingAvailable = linkSharingAvailable)

                ShareJournalActions(
                    linkSharingAvailable = linkSharingAvailable,
                    onShareQrCode = onShareQrCode,
                    onShareJournal = onShareJournal,
                    onShareToInstagram = onShareToInstagram,
                )
            }
        }
    }
}

@Composable
private fun ShareJournalPrimaryPane(
    journal: Journal,
    modifier: Modifier = Modifier,
    previewCoverPainter: Painter? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        val verticalPadding = if (maxHeight < 520.dp) Spacing.sm else Spacing.lg
        val maxCardWidth =
            minOf(480.dp, maxOf(100.dp, (maxHeight - verticalPadding * 2 - 8.dp) * AspectRatios.JOURNAL_COVER))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Spacing.lg, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ShareJournalCard(
                journal = journal,
                modifier = Modifier.widthIn(max = maxCardWidth),
                previewCoverPainter = previewCoverPainter,
                showInfoStrip = false,
            )
        }
    }
}

@Composable
private fun ShareJournalActionPane(
    linkSharingAvailable: Boolean,
    onShareToInstagram: () -> Unit,
    onShareQrCode: () -> Unit,
    onShareJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.CenterVertically),
        ) {
            ShareJournalDescription(linkSharingAvailable = linkSharingAvailable)

            ShareJournalActions(
                linkSharingAvailable = linkSharingAvailable,
                onShareQrCode = onShareQrCode,
                onShareJournal = onShareJournal,
                onShareToInstagram = onShareToInstagram,
            )
        }
    }
}

@Composable
private fun ShareJournalDescription(linkSharingAvailable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("share_journal_access"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Icon(
            if (linkSharingAvailable) Icons.Rounded.Public else Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    if (linkSharingAvailable) {
                        Res.string.share_journal_description
                    } else {
                        Res.string.share_journal_private_description
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareJournalActions(
    linkSharingAvailable: Boolean,
    onShareQrCode: () -> Unit,
    onShareJournal: () -> Unit,
    onShareToInstagram: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (linkSharingAvailable) {
            ShareJournalLinkActions(onShareQrCode = onShareQrCode, onShareJournal = onShareJournal)
        }

        Button(
            onClick = onShareToInstagram,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("share_journal_instagram_action"),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Text(text = stringResource(Res.string.share_to_instagram))
        }
    }
}

@Composable
private fun ShareJournalLinkActions(
    onShareQrCode: () -> Unit,
    onShareJournal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Button(
            onClick = onShareQrCode,
            modifier =
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("share_journal_qr_action"),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = stringResource(Res.string.share_qr_code),
                modifier = Modifier.size(24.dp),
            )
        }

        Button(
            onClick = onShareJournal,
            modifier =
                Modifier
                    .weight(3f)
                    .height(56.dp)
                    .testTag("share_journal_sheet_action"),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(text = stringResource(Res.string.share))
        }
    }
}

/**
 * A share-specific journal card that renders the journal cover shape
 * with an optional info strip showing the title and last updated date.
 */
@Composable
private fun ShareJournalCard(
    journal: Journal,
    modifier: Modifier = Modifier,
    previewCoverPainter: Painter? = null,
    showInfoStrip: Boolean = true,
) {
    val coverColor = remember(journal.id) { deriveCoverColor(journal.id) }
    val hasCoverImage = journal.coverImageUri != null
    val coverTextColor =
        remember(coverColor, hasCoverImage) {
            if (hasCoverImage) {
                Color.White.copy(alpha = 0.95f)
            } else if (coverColor.luminance() > 0.5f) {
                Color.Black.copy(alpha = 0.87f)
            } else {
                Color.White.copy(alpha = 0.95f)
            }
        }

    Surface(
        modifier =
            modifier
                .shadow(elevation = 4.dp, shape = JournalShape)
                .testTag("share_journal_preview"),
        shape = JournalShape,
    ) {
        Column {
            // Cover area with title overlay
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(AspectRatios.JOURNAL_COVER)
                        .background(coverColor),
                contentAlignment = Alignment.BottomStart,
            ) {
                journal.coverImageUri?.let { uri ->
                    if (previewCoverPainter == null) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag("share_journal_cover_image"),
                        )
                    } else {
                        Image(
                            painter = previewCoverPainter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag("share_journal_cover_image"),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.45f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.7f),
                                    ),
                                ),
                    )
                }
                Text(
                    text = journal.title,
                    modifier = Modifier.padding(Spacing.lg),
                    style = MaterialTheme.typography.titleMedium,
                    color = coverTextColor,
                )
            }

            if (showInfoStrip) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = journal.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.last_updated_prefix, journal.lastUpdated.toReadableDateShort()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Data class for journal sharing information.
 *
 * @param journalId String ID of the journal to share
 */
data class JournalShareData(
    val journalId: String,
)
