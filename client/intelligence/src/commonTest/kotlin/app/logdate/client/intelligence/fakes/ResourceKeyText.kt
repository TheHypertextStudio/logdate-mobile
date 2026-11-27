package app.logdate.client.intelligence.fakes

import app.logdate.client.text.UiText

/**
 * Stands in for Compose resource lookup in tests, which cannot read resources.
 *
 * Writes a [UiText] as its resource key followed by its arguments, for example
 * `rewind_resolution_tone(joyful)`, so a test can see which words the code chose without
 * loading them.
 */
val resourceKeyText: suspend (UiText) -> String = { text -> text.toResourceKeyString() }

fun UiText.toResourceKeyString(): String =
    when (this) {
        is UiText.Resource -> "${resource.key}(${args.joinToString { it.toResourceKeyArgument() }})"
        is UiText.Plural -> "${resource.key}[$quantity](${args.joinToString { it.toResourceKeyArgument() }})"
        is UiText.Raw -> value
        is UiText.Joined -> parts.joinToString(separator) { it.toResourceKeyString() }
    }

private fun Any.toResourceKeyArgument(): String = if (this is UiText) toResourceKeyString() else toString()
