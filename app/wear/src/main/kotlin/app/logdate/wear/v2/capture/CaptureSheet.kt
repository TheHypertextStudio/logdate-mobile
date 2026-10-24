package app.logdate.wear.v2.capture

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.FailureConfirmationDialogContent
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.SuccessConfirmationDialogContent
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.logdate.wear.R
import app.logdate.wear.capture.Feeling

object CaptureSheetTags {
    const val LIST = "capture_sheet"
    const val WRITE = "capture_write"
    const val MOOD = "capture_mood"
    const val PHONE_CAMERA = "capture_phone_camera"

    fun feeling(feeling: Feeling): String = "capture_feeling_${feeling.tag}"
}

/**
 * The + sheet: Write, Mood with the five feelings under it, and Phone camera while the phone is in
 * reach. Opening the feelings scrolls to the first of them, so the first few are on screen. Nothing
 * in it can be tapped while a save runs.
 */
@Composable
fun CaptureSheet(
    state: CaptureSheetUiState,
    onWrite: () -> Unit,
    onToggleFeelings: () -> Unit,
    onFeeling: (Feeling) -> Unit,
    onPhoneCamera: () -> Unit,
    modifier: Modifier = Modifier,
    listState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
) {
    val spec = rememberTransformationSpec()
    val enabled = !state.saving
    LaunchedEffect(state.choosingFeeling) {
        if (state.choosingFeeling) listState.animateScrollToItem(FIRST_FEELING_ITEM_INDEX)
    }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier.testTag(CaptureSheetTags.LIST)) {
            item {
                ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_capture_title))
                }
            }
            item { CaptureButton(stringResource(R.string.wear_capture_write), CaptureSheetTags.WRITE, enabled, onWrite, spec) }
            item { CaptureButton(stringResource(R.string.wear_capture_mood), CaptureSheetTags.MOOD, enabled, onToggleFeelings, spec) }
            if (state.choosingFeeling) {
                items(count = Feeling.entries.size) { index ->
                    val feeling = Feeling.entries[index]
                    val label = "${feeling.emoji} ${stringResource(feeling.labelRes)}"
                    CaptureButton(label, CaptureSheetTags.feeling(feeling), enabled, { onFeeling(feeling) }, spec)
                }
            }
            if (state.phoneInReach) {
                item {
                    CaptureButton(
                        text = stringResource(R.string.wear_capture_phone_camera),
                        tag = CaptureSheetTags.PHONE_CAMERA,
                        enabled = enabled,
                        onClick = onPhoneCamera,
                        spec = spec,
                    )
                }
            }
        }
    }
}

/** After the header, Write and Mood. */
private const val FIRST_FEELING_ITEM_INDEX = 3

@Composable
private fun TransformingLazyColumnItemScope.CaptureButton(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    spec: TransformationSpec,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag(tag),
        enabled = enabled,
        transformation = SurfaceTransformation(spec),
    ) {
        Text(text = text)
    }
}

/** "Saved", which returns to Today when it goes, or "Couldn’t save", which leaves the sheet open. */
@Composable
fun CaptureConfirmation(
    result: CaptureResult,
    onSavedDismissed: () -> Unit,
    onFailedDismissed: () -> Unit,
) {
    val style = ConfirmationDialogDefaults.curvedTextStyle
    val saved = stringResource(R.string.wear_capture_saved)
    val failed = stringResource(R.string.wear_capture_failed)
    SuccessConfirmationDialog(
        visible = result == CaptureResult.SAVED,
        onDismissRequest = onSavedDismissed,
        curvedText = { confirmationDialogCurvedText(saved, style) },
    )
    FailureConfirmationDialog(
        visible = result == CaptureResult.FAILED,
        onDismissRequest = onFailedDismissed,
        curvedText = { confirmationDialogCurvedText(failed, style) },
    )
}

/** What [CaptureConfirmation] shows, without the dialog window, for screenshots. */
@Composable
fun CaptureConfirmationContent(result: CaptureResult) {
    val style = ConfirmationDialogDefaults.curvedTextStyle
    when (result) {
        CaptureResult.SAVED -> {
            val saved = stringResource(R.string.wear_capture_saved)
            SuccessConfirmationDialogContent(curvedText = { confirmationDialogCurvedText(saved, style) })
        }

        CaptureResult.FAILED -> {
            val failed = stringResource(R.string.wear_capture_failed)
            FailureConfirmationDialogContent(curvedText = { confirmationDialogCurvedText(failed, style) })
        }

        CaptureResult.NONE -> {
            Unit
        }
    }
}
