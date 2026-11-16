@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val HeroSize = 88.dp
private val HeroIconSize = 44.dp

/** A tonal circle for a [StepScaffold] hero, holding an icon, a monogram, or similar mark. */
@Composable
fun StepHero(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(HeroSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
            content()
        }
    }
}

/** A [StepHero] holding a single icon. The icon is decorative; the step title carries meaning. */
@Composable
fun StepHeroIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    StepHero(modifier = modifier) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(HeroIconSize))
    }
}

/**
 * The main action of a step: one wide, tall button that changes shape under the finger.
 *
 * Every step uses this for its main action, so it is the same size and in the same place from one
 * step to the next. While [busy], the label keeps its place at zero alpha under a spinner, so the
 * button never changes size and nothing around it shifts. The label stays in the semantics tree
 * for screen readers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = ButtonDefaults.textStyleFor(height),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(if (busy) 0f else 1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            }
        }
    }
}

/**
 * The other thing a step lets someone do, set in plain text under the main action so the two are
 * never mistaken for each other. A [quiet] one is for a path few people need: it is there, and it
 * does not compete.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = enabled,
        modifier = modifier,
        colors =
            if (quiet) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) {
        Text(
            text = text,
            style = if (quiet) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The one line of text a step asks for: a filled, rounded field with no outline and no underline.
 *
 * Every step that asks for something uses this, so a field looks the same from one step to the
 * next. It takes the same slots as a Material text field. [containerColor] is for a field that
 * sits on something other than the step's own background.
 */
@Composable
fun StepTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.large,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                disabledContainerColor = containerColor,
                errorContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
    )
}
