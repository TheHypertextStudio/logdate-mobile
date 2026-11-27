package app.logdate.feature.editor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.logdate.ui.platform.PlatformPredictiveBackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The actual document geometry has one clock per memory, including direct predictive gestures. */
internal class RetainedEditorFocusMotion(
    private val scope: CoroutineScope,
    private val initialFocus: Uuid?,
    private val currentFocus: () -> Uuid?,
    private val reduceMotion: () -> Boolean,
    private val settleArrivingFocus: () -> Boolean,
    private val dismissAction: () -> (() -> Boolean),
    private val onPrepare: () -> Unit,
    private val onProgress: (Float) -> Unit,
    private val onCommit: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val owners = mutableMapOf<Uuid, Animatable<Float, AnimationVector1D>>()
    private var returning by mutableStateOf(false)
    private var gesture: Gesture? = null
    private var invalidatedGesture = false
    private var gestureJob: Job? = null
    private var generation = 0L

    private data class Gesture(
        val id: Uuid,
        val visibleProgress: Float,
        val generation: Long,
        val dismiss: () -> Boolean,
    )

    val targetId: Uuid? get() = if (returning) null else currentFocus()
    val progress: Map<Uuid, Float> get() = owners.mapValues { it.value.value }
    val surfaceProgress: Float get() = currentFocus()?.let { owners[it]?.value } ?: owners.values.maxOfOrNull { it.value } ?: 0f

    /**
     * A memory starts in focus when it was focused from the start, or when it is the first memory
     * of an entry that is still opening. The second case is the preferred mode (for example the
     * camera) arriving while the create button grows into the entry: it lands in focus inside that
     * one motion instead of opening a second, separate focus motion after it.
     */
    fun ensure(id: Uuid) {
        if (id in owners) return
        val arrivesFocused = owners.isEmpty() && id == currentFocus() && settleArrivingFocus()
        owners[id] = Animatable(if (id == initialFocus || arrivesFocused) 1f else 0f)
    }

    suspend fun retarget(ids: Set<Uuid>) {
        generation++
        gestureJob?.cancel()
        if (gesture != null) invalidatedGesture = true
        gesture = null
        returning = false
        val focused = currentFocus()
        coroutineScope {
            owners.forEach { (id, value) ->
                launch { value.moveTo(if (id == focused) 1f else 0f) }
            }
        }
        owners.keys.retainAll(ids)
    }

    fun seekBack(fraction: Float) {
        if (invalidatedGesture) return
        val id = currentFocus() ?: return
        val value = owners[id] ?: return
        val origin =
            gesture?.takeIf { it.id == id } ?: Gesture(id, value.value, generation, dismissAction()).also {
                onPrepare()
                gesture = it
                returning = true
            }
        gestureJob?.cancel()
        gestureJob =
            scope.launch {
                value.snapTo(if (reduceMotion()) origin.visibleProgress else origin.visibleProgress * (1f - fraction.coerceIn(0f, 1f)))
                onProgress(fraction)
            }
    }

    fun cancelBack() {
        if (invalidatedGesture) {
            invalidatedGesture = false
            return
        }
        val origin = gesture ?: return
        gesture = null
        gestureJob?.cancel()
        if (currentFocus() != origin.id || generation != origin.generation) return
        returning = false
        onCancel()
        gestureJob = scope.launch { owners[origin.id]?.moveTo(1f) }
    }

    fun commitBack() {
        if (invalidatedGesture) {
            invalidatedGesture = false
            return
        }
        val id = currentFocus() ?: return
        val origin = gesture ?: Gesture(id, owners[id]?.value ?: 0f, generation, dismissAction()).also { onPrepare() }
        gesture = null
        gestureJob?.cancel()
        returning = true
        onCommit()
        gestureJob =
            scope.launch {
                owners[origin.id]?.moveTo(0f)
                if (currentFocus() == origin.id && generation == origin.generation && !origin.dismiss()) {
                    returning = false
                    owners[origin.id]?.moveTo(1f)
                }
            }
    }

    private suspend fun Animatable<Float, AnimationVector1D>.moveTo(target: Float) {
        if (reduceMotion()) snapTo(target) else animateTo(target, blockFocusSpring())
    }
}

@Composable
internal fun rememberRetainedEditorFocusMotion(
    focusedId: Uuid?,
    ids: Set<Uuid>,
    reduceMotion: Boolean,
    onDismiss: () -> Boolean,
    onPrepare: () -> Unit,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    inputGeneration: Uuid? = null,
    settleArrivingFocus: () -> Boolean = { false },
): RetainedEditorFocusMotion {
    val scope = rememberCoroutineScope()
    val currentFocus by rememberUpdatedState(focusedId)
    val reduced by rememberUpdatedState(reduceMotion)
    val settleArrival by rememberUpdatedState(settleArrivingFocus)
    val dismiss by rememberUpdatedState(onDismiss)
    val prepare by rememberUpdatedState(onPrepare)
    val progress by rememberUpdatedState(onProgress)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val motion =
        remember {
            RetainedEditorFocusMotion(scope, focusedId, {
                currentFocus
            }, { reduced }, { settleArrival() }, { dismiss }, { prepare() }, { progress(it) }, { commit() }, { cancel() })
        }
    ids.forEach(motion::ensure)
    LaunchedEffect(focusedId, inputGeneration, ids, reduceMotion) { motion.retarget(ids) }
    PlatformPredictiveBackHandler(
        enabled = focusedId != null,
        onProgress = motion::seekBack,
        onBack = motion::commitBack,
        onCancel = motion::cancelBack,
    )
    return motion
}
