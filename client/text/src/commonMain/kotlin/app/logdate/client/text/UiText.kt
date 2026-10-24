package app.logdate.client.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text that UI state carries to the screen.
 *
 * A ViewModel or mapper names the string resource and its arguments instead of writing English words.
 * Composition turns that into words for the current locale with [asString], so the words follow the
 * device language and state stays comparable in tests. Code that runs outside composition, such as a
 * notification or a share sheet, calls [resolve].
 *
 * [Raw] holds text that is already final: a server message, a device name, or the person's own content.
 * An argument can itself be a [UiText], for example a device name inside a sentence. It is resolved
 * before the sentence is formatted.
 */
sealed interface UiText {
    data class Resource(
        val resource: StringResource,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        val resource: PluralStringResource,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Raw(
        val value: String,
    ) : UiText
}

fun StringResource.toUiText(vararg args: Any): UiText = UiText.Resource(this, args.toList())

fun PluralStringResource.toUiText(
    quantity: Int,
    vararg args: Any,
): UiText = UiText.Plural(this, quantity, args.toList())

fun String.toUiText(): UiText = UiText.Raw(this)

@Composable
fun UiText.asString(): String =
    when (this) {
        is UiText.Resource -> {
            if (args.isEmpty()) {
                stringResource(resource)
            } else {
                stringResource(resource, *args.map { it.asArgument() }.toTypedArray())
            }
        }

        is UiText.Plural -> {
            pluralStringResource(resource, quantity, *args.map { it.asArgument() }.toTypedArray())
        }

        is UiText.Raw -> {
            value
        }
    }

suspend fun UiText.resolve(): String =
    when (this) {
        is UiText.Resource -> {
            if (args.isEmpty()) {
                getString(resource)
            } else {
                getString(resource, *args.map { it.resolveArgument() }.toTypedArray())
            }
        }

        is UiText.Plural -> {
            getPluralString(resource, quantity, *args.map { it.resolveArgument() }.toTypedArray())
        }

        is UiText.Raw -> {
            value
        }
    }

@Composable
private fun Any.asArgument(): Any = if (this is UiText) asString() else this

private suspend fun Any.resolveArgument(): Any = if (this is UiText) resolve() else this
