package app.logdate.client.datastore.featureflags

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Qualified store for phone activity consent, which belongs to one Android installation. */
const val PHONE_ACTIVITY_CONSENT_STORE = "phoneActivityConsentStore"

/**
 * Reads and writes [FeatureFlag] values.
 *
 * Flags live in the same preferences as the settings a user sets directly, under the key each flag
 * declares. That is deliberate: the three flags that predate this type were already stored that
 * way and are read through their own accessors elsewhere, so both paths see the same value rather
 * than disagreeing about whether a feature is on.
 */
interface FeatureFlagStore {
    /**
     * Observes whether [flag] is enabled, starting from its default for anyone who has never set
     * it.
     */
    fun observe(flag: FeatureFlag): Flow<Boolean>

    /**
     * Reads whether [flag] is currently enabled.
     *
     * Prefer [observe] where the caller can react to a change; this is for one-shot decisions.
     */
    suspend fun isEnabled(flag: FeatureFlag): Boolean = observe(flag).first()

    /**
     * Turns [flag] on or off for this installation.
     *
     * Setting a flag to its effective default clears the stored value instead of writing it, so
     * the flag follows the build's default again. A stored value is read before that default.
     */
    suspend fun setEnabled(
        flag: FeatureFlag,
        enabled: Boolean,
    )
}

/**
 * The [FeatureFlagStore] backed by the app's preference store.
 */
class DataStoreFeatureFlagStore(
    private val userPreferences: DataStore<Preferences>,
    private val defaultOverrides: Map<FeatureFlag, Boolean> = emptyMap(),
) : FeatureFlagStore {
    override fun observe(flag: FeatureFlag): Flow<Boolean> =
        userPreferences.data.map { preferences ->
            flag.availableForLaunch && (preferences[booleanPreferencesKey(flag.key)] ?: effectiveDefault(flag))
        }

    override suspend fun setEnabled(
        flag: FeatureFlag,
        enabled: Boolean,
    ) {
        val key = booleanPreferencesKey(flag.key)
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                if (enabled == effectiveDefault(flag)) {
                    remove(key)
                } else {
                    this[key] = enabled
                }
            }
        }
    }

    private fun effectiveDefault(flag: FeatureFlag): Boolean = defaultOverrides[flag] ?: flag.defaultEnabled
}
