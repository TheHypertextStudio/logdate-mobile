package app.logdate.ui.common

import androidx.compose.ui.Modifier

/**
 * Lets a person start writing with a pen on a control that opens a text field rather than being
 * one, such as the workspace search pill or the entry page's Write choice.
 *
 * When a pen starts writing on this element, [onStart] runs and must open and focus a text field
 * marked with [handwritingDelegate]; the keyboard then types the handwriting into that field. A
 * finger or mouse still uses the element's own click handling.
 *
 * Android 14 and later, with a keyboard that supports handwriting. Elsewhere this does nothing.
 */
expect fun Modifier.handwritingDelegator(onStart: () -> Unit): Modifier

/**
 * Marks a text field as the place handwriting started on a [handwritingDelegator] goes. The field
 * must be focused when it appears, or the keyboard drops the handwriting.
 *
 * Android 14 and later. Elsewhere this does nothing.
 */
expect fun Modifier.handwritingDelegate(): Modifier
