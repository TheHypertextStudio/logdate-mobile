@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * A flag store that holds the People flag only. Its value starts unread when [initial] is `null`,
 * the way the preference store is before its first read, and can then be [set].
 *
 * Every other flag stays unread and cannot be written, so a gate that follows the wrong flag never
 * sees a value here.
 */
internal class StoredPeopleFlag(
    initial: Boolean? = null,
) : FeatureFlagStore {
    private val stored = MutableStateFlow(initial)

    override fun observe(flag: FeatureFlag): Flow<Boolean> {
        if (flag != FeatureFlag.PEOPLE) return emptyFlow()
        return stored.filterNotNull()
    }

    override suspend fun setEnabled(
        flag: FeatureFlag,
        enabled: Boolean,
    ) {
        check(flag == FeatureFlag.PEOPLE) { "Only the People flag is stored here, not ${flag.key}" }
        stored.value = enabled
    }

    fun set(enabled: Boolean) {
        stored.value = enabled
    }
}

/** The app's People switch over [flags], following the stored value as soon as it changes. */
internal fun peopleSwitch(flags: FeatureFlagStore): PeopleSwitch = PeopleSwitch(flags, CoroutineScope(Dispatchers.Unconfined))

/** Hosts [content] under a resumed lifecycle, which lifecycle-aware collection needs before it reads. */
@Composable
internal fun ResumedLifecycle(content: @Composable () -> Unit) {
    val owner = remember { ResumedLifecycleOwner() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

private class ResumedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    override val lifecycle: Lifecycle = registry
}
