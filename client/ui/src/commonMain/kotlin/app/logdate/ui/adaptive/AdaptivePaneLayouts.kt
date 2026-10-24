@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.rememberFoldableLayoutInfo

@Immutable
enum class AdaptiveWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

@Immutable
data class AdaptivePaneLayoutInfo(
    val widthClass: AdaptiveWidthClass,
    val showSupportingPane: Boolean,
)

@Composable
fun AdaptivePaneLayout(
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    foldableLayoutInfo: FoldableLayoutInfo = rememberFoldableLayoutInfo(),
    supportingPaneBreakpoint: Dp = 840.dp,
    supportingPaneWidth: Dp = 360.dp,
    paneSpacing: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    mainPaneMinWidth: Dp = 320.dp,
    mainPaneMaxWidth: Dp = 720.dp,
    supportingPaneMaxWidth: Dp = 420.dp,
    supportingPane: @Composable (AdaptivePaneLayoutInfo) -> Unit = {},
    mainPane: @Composable (AdaptivePaneLayoutInfo) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val horizontalContentPadding =
            contentPadding.calculateLeftPadding(layoutDirection) +
                contentPadding.calculateRightPadding(layoutDirection)
        val requiredSupportingPaneWidth =
            horizontalContentPadding + paneSpacing + supportingPaneWidth + mainPaneMinWidth
        val effectiveSupportingPaneBreakpoint =
            maxOf(supportingPaneBreakpoint, requiredSupportingPaneWidth)
        val widthClass =
            when {
                maxWidth >= supportingPaneBreakpoint -> AdaptiveWidthClass.EXPANDED
                maxWidth >= 600.dp -> AdaptiveWidthClass.MEDIUM
                else -> AdaptiveWidthClass.COMPACT
            }
        val layoutInfo =
            AdaptivePaneLayoutInfo(
                widthClass = widthClass,
                showSupportingPane = maxWidth >= effectiveSupportingPaneBreakpoint,
            )
        val foldableSplitLayout =
            calculateFoldableSplitLayout(
                containerWidth = maxWidth,
                containerHeight = maxHeight,
                layoutInfo = foldableLayoutInfo,
                minPaneWidth = mainPaneMinWidth,
            )

        when (val splitLayout = foldableSplitLayout) {
            is FoldableSplitLayout.Vertical -> {
                val splitLayoutInfo = layoutInfo.copy(showSupportingPane = true)
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier =
                            Modifier
                                .width(splitLayout.leftPane.width)
                                .fillMaxHeight()
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = mainPaneMaxWidth),
                        ) {
                            mainPane(splitLayoutInfo)
                        }
                    }
                    Spacer(
                        modifier =
                            Modifier
                                .width(splitLayout.hingeBounds.width)
                                .fillMaxHeight(),
                    )
                    Box(
                        modifier =
                            Modifier
                                .width(splitLayout.rightPane.width)
                                .fillMaxHeight()
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = supportingPaneMaxWidth),
                        ) {
                            supportingPane(splitLayoutInfo)
                        }
                    }
                }
            }

            is FoldableSplitLayout.Horizontal -> {
                val splitLayoutInfo = layoutInfo.copy(showSupportingPane = true)
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(splitLayout.topPane.height)
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = mainPaneMaxWidth),
                        ) {
                            mainPane(splitLayoutInfo)
                        }
                    }
                    Spacer(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(splitLayout.hingeBounds.height),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(splitLayout.bottomPane.height)
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = supportingPaneMaxWidth),
                        ) {
                            supportingPane(splitLayoutInfo)
                        }
                    }
                }
            }

            FoldableSplitLayout.None -> {
                if (layoutInfo.showSupportingPane) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                        horizontalArrangement = Arrangement.spacedBy(paneSpacing),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = mainPaneMaxWidth),
                            ) {
                                mainPane(layoutInfo)
                            }
                        }
                        Box(
                            modifier =
                                Modifier
                                    .width(supportingPaneWidth)
                                    .widthIn(max = supportingPaneMaxWidth)
                                    .fillMaxHeight(),
                        ) {
                            supportingPane(layoutInfo)
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(contentWindowInsets)
                                .padding(contentPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = mainPaneMaxWidth),
                        ) {
                            mainPane(layoutInfo)
                        }
                    }
                }
            }
        }
    }
}
