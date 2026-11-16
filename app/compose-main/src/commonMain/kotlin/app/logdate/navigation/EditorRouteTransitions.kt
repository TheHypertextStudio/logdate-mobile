package app.logdate.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.core.main.HomeRoute
import app.logdate.ui.navigation.routeClass
import kotlin.reflect.KClass

private typealias SceneTransition = AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform
private typealias PredictiveSceneTransition = AnimatedContentTransitionScope<Scene<*>>.(Int) -> ContentTransform

private fun sceneRouteClass(scene: Scene<*>?): KClass<out NavKey>? = scene?.entries?.lastOrNull()?.routeClass()

private val transitionEasing = FastOutSlowInEasing
private const val ROUTE_SLIDE_MILLIS = 380

private fun forwardSlideFade(): ContentTransform =
    (
        slideInHorizontally(tween(ROUTE_SLIDE_MILLIS, easing = transitionEasing)) { it } +
            fadeIn(tween(300, easing = transitionEasing))
    ) togetherWith (
        slideOutHorizontally(tween(ROUTE_SLIDE_MILLIS, easing = transitionEasing)) { -it / 3 } +
            fadeOut(tween(240, easing = transitionEasing))
    )

private fun popSlideFade(): ContentTransform =
    (
        slideInHorizontally(tween(ROUTE_SLIDE_MILLIS, easing = transitionEasing)) { -it / 3 } +
            fadeIn(tween(300, easing = transitionEasing))
    ) togetherWith (
        slideOutHorizontally(tween(ROUTE_SLIDE_MILLIS, easing = transitionEasing)) { it } +
            fadeOut(tween(240, easing = transitionEasing))
    )

private val editorRouteTransitions =
    RouteTransitions(
        forward = {
            if (sceneRouteClass(initialState) == HomeRoute::class) {
                EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
            } else {
                forwardSlideFade()
            }
        },
        pop = {
            if (sceneRouteClass(targetState) == HomeRoute::class) {
                EnterTransition.None togetherWith fadeOut()
            } else {
                popSlideFade()
            }
        },
        predictivePop = { _ ->
            if (sceneRouteClass(targetState) == HomeRoute::class) {
                EnterTransition.None togetherWith fadeOut()
            } else {
                popSlideFade()
            }
        },
    )

internal val editorRouteTransitionMetadata: Map<String, Any> =
    editorRouteTransitions.toMetadata()

internal val onboardingRouteTransitionMetadata: Map<String, Any> =
    RouteTransitions(
        forward = { forwardSlideFade() },
        pop = { popSlideFade() },
        predictivePop = { _ -> popSlideFade() },
    ).toMetadata()

internal val homeRouteTransitionMetadata: Map<String, Any> =
    RouteTransitions(
        forward = {
            fadeIn(tween(420, easing = transitionEasing)) togetherWith
                fadeOut(tween(280, easing = transitionEasing))
        },
        pop = {
            fadeIn(tween(320, easing = transitionEasing)) togetherWith
                fadeOut(tween(240, easing = transitionEasing))
        },
        predictivePop = { _ ->
            fadeIn(tween(320, easing = transitionEasing)) togetherWith
                fadeOut(tween(240, easing = transitionEasing))
        },
    ).toMetadata()

private data class RouteTransitions(
    val forward: SceneTransition,
    val pop: SceneTransition,
    val predictivePop: PredictiveSceneTransition,
) {
    fun toMetadata(): Map<String, Any> =
        NavDisplay.transitionSpec(forward) +
            NavDisplay.popTransitionSpec(pop) +
            NavDisplay.predictivePopTransitionSpec(predictivePop)
}
