package app.logdate.feature.editor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.text.rememberInlineMarkdownVisualTransformation
import app.logdate.ui.platform.PlatformIcons
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.add_a_caption
import logdate.client.ui.generated.resources.common_delete
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Suppress("ktlint:standard:function-naming")
@Composable
fun DeleteMediaButton(
    onClick: () -> Unit,
    contentDescription: String = stringResource(UiRes.string.common_delete),
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                    shape = CircleShape,
                ),
    ) {
        Icon(
            painter = PlatformIcons.delete(),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun OverlayCaptionField(
    caption: String,
    onCaptionChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = caption,
        visualTransformation = rememberInlineMarkdownVisualTransformation(),
        onValueChange = onCaptionChanged,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        decorationBox = { inner ->
            if (caption.isEmpty()) {
                Text(
                    stringResource(Res.string.add_a_caption),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            inner()
        },
        maxLines = 3,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Overlay area that combines a dark gradient scrim with the caption text field.
 * Place this at the bottom of a [Box] containing the media content.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaOverlayCaptionArea(
    caption: String,
    onCaptionChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                ).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        OverlayCaptionField(caption = caption, onCaptionChanged = onCaptionChanged)
    }
}
