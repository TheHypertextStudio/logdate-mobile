package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.text.UiText

/**
 * Returns the text itself when it is already final, such as a place's own name, so the places list
 * can search and sort by it without resolving resources. Resource-backed text returns an empty string.
 */
internal fun UiText.literalOrEmpty(): String = (this as? UiText.Raw)?.value.orEmpty()
