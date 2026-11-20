package app.logdate.client.sync.v2.link

import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** How the phone links a watch: pair only, or to [accountId]. */
data class LinkPlan(
    val mode: LinkMode,
    val accountId: String? = null,
    val serverOrigin: String? = null,
)

sealed interface LinkBundleResult {
    data class Ready(
        val bundle: LinkBundle,
    ) : LinkBundleResult

    /** @property serverUnreachable The account's server gave no answer, so nothing is wrong with the account or the watch. */
    data class Unavailable(
        val failure: PhoneLinkFailure,
        val serverUnreachable: Boolean = false,
    ) : LinkBundleResult
}

/** Decides the plan for a watch and builds the bundle for it. */
interface LinkBundleSource {
    /** Emits whenever something that changes [plan] changed, such as signing in. */
    fun changes(): Flow<Unit>

    suspend fun plan(watchFeatures: Set<String>): LinkPlan

    suspend fun bundle(plan: LinkPlan): LinkBundleResult
}

/** Pair-only linking: what a phone with no account offers. */
object PairOnlyBundleSource : LinkBundleSource {
    override fun changes(): Flow<Unit> = flowOf(Unit)

    override suspend fun plan(watchFeatures: Set<String>): LinkPlan = LinkPlan(LinkMode.PAIR)

    override suspend fun bundle(plan: LinkPlan): LinkBundleResult = LinkBundleResult.Ready(LinkBundle(mode = LinkMode.PAIR))
}
