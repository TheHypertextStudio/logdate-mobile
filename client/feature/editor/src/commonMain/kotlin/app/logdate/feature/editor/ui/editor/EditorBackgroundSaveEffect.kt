package app.logdate.feature.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun EditorBackgroundSaveEffect(onSaveLatest: () -> Unit) {
    val latestSave = rememberUpdatedState(onSaveLatest)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { latestSave.value() }
    DisposableEffect(Unit) {
        onDispose { latestSave.value() }
    }
}
