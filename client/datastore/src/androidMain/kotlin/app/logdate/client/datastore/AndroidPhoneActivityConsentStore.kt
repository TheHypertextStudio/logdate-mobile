package app.logdate.client.datastore

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import java.io.File

private val phoneActivityConsentStores = mutableMapOf<String, DataStore<Preferences>>()

/** Restoring a journal or its portable preferences cannot link activity on another phone. */
fun createAndroidPhoneActivityConsentStore(
    context: Context,
    portable: FeatureFlagStore,
    canAdoptLegacy: () -> Boolean,
): FeatureFlagStore =
    synchronized(phoneActivityConsentStores) {
        val file = File(context.noBackupFilesDir, "phone-activity-consent.preferences_pb").canonicalFile
        DataStoreFeatureFlagStore(
            phoneActivityConsentStores.getOrPut(file.path) {
                PreferenceDataStoreFactory.create(
                    migrations = listOf(PhoneActivityLegacyConsentMigration(portable, canAdoptLegacy)),
                    produceFile = { file },
                )
            },
        )
    }

internal class PhoneActivityLegacyConsentMigration(
    private val portable: FeatureFlagStore,
    private val canAdoptLegacy: () -> Boolean,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[migrated] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (canAdoptLegacy() && portable.isEnabled(FeatureFlag.ACTIVITY_AWARENESS)) {
                this[booleanPreferencesKey(FeatureFlag.ACTIVITY_AWARENESS.key)] = true
            }
            this[migrated] = true
        }

    override suspend fun cleanUp() = Unit

    private companion object {
        val migrated = booleanPreferencesKey("legacy_activity_choice_checked")
    }
}
