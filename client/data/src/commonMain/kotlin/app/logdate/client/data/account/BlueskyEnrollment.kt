package app.logdate.client.data.account

import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import kotlinx.serialization.Serializable

@Serializable
internal class BlueskyEnrollment(
    val attemptId: String,
    val backendOrigin: String,
    val step: BlueskyEnrollmentStep,
    val expiresAtMillis: Long,
    val ownerId: String,
    val completionStarted: Boolean = false,
) {
    override fun toString(): String = "BlueskyEnrollment()"
}

@Serializable
internal sealed class BlueskyEnrollmentStep {
    abstract val token: String

    final override fun toString(): String = "BlueskyEnrollmentStep()"

    @Serializable
    class Identity(
        override val token: String,
        val nextStep: BlueskyAccountNextStep,
    ) : BlueskyEnrollmentStep()

    @Serializable
    class Registration(
        override val token: String,
        val profile: BlueskyEnrollmentProfile,
        val options: PasskeyRegistrationOptions,
    ) : BlueskyEnrollmentStep()

    @Serializable
    class Authentication(
        override val token: String,
        val options: PasskeyAuthenticationOptions,
    ) : BlueskyEnrollmentStep()
}

@Serializable
internal class BlueskyEnrollmentProfile(
    val ownerId: String,
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
) {
    override fun toString(): String = "BlueskyEnrollmentProfile()"
}
