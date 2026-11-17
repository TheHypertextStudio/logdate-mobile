@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface OnboardingBaseRoute : NavKey

/** Marker route that resolves to [OnboardingStart] when entered. */
@Serializable
data object OnboardingRoute : OnboardingBaseRoute

@Serializable
data object OnboardingStart : OnboardingBaseRoute

@Serializable
data object PersonalIntro : OnboardingBaseRoute

@Serializable
data object AppOverview : OnboardingBaseRoute

@Serializable
data object MemoryImport : OnboardingBaseRoute

@Serializable
data object MemorySelection : OnboardingBaseRoute

@Serializable
data object AccountCreation : OnboardingBaseRoute

@Serializable
data object SignIn : OnboardingBaseRoute

@Serializable
data object RecoveryPhrase : OnboardingBaseRoute

@Serializable
data object BirthdayIntro : OnboardingBaseRoute

@Serializable
data object FeatureRecommendations : OnboardingBaseRoute

@Serializable
data object FeatureDayBoundaries : OnboardingBaseRoute

@Serializable
data object FeatureLocationTimeline : OnboardingBaseRoute

@Serializable
data object FeatureNotifications : OnboardingBaseRoute

@Serializable
data object FeatureWatch : OnboardingBaseRoute

@Serializable
data object OnboardingComplete : OnboardingBaseRoute

@Serializable
data object FirstEntryImportOffer : OnboardingBaseRoute

@Serializable
data object FirstEntryMemorySelection : OnboardingBaseRoute

@Serializable
data object WelcomeBack : OnboardingBaseRoute
