package app.logdate.feature.core.account.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.client.device.auth.BlueskyAppReturn
import kotlinx.serialization.Serializable

@Serializable
data class BlueskySignInRoute(
    val attemptId: String? = null,
) : NavKey {
    override fun toString(): String = "BlueskySignInRoute()"
}

fun blueskyReturnRoute(url: String): BlueskySignInRoute? = BlueskyAppReturn.attemptId(url)?.let { BlueskySignInRoute(it) }
