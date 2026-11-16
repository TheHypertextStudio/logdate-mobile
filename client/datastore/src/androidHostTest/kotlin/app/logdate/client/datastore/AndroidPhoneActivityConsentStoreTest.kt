package app.logdate.client.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPhoneActivityConsentStoreTest {
    @Test
    fun restoredChoicesCannotEnableThisInstallationButAnExistingLinkSurvives() =
        runTest {
            val root = createTempDirectory("phone-activity-consent").toFile()
            try {
                val portable =
                    DataStoreFeatureFlagStore(
                        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(root, "portable.preferences_pb") }),
                    )
                portable.setEnabled(FeatureFlag.ACTIVITY_AWARENESS, true)

                fun installation(
                    name: String,
                    provenLocal: Boolean,
                ) = DataStoreFeatureFlagStore(
                    PreferenceDataStoreFactory.create(
                        migrations =
                            listOf(
                                PhoneActivityLegacyConsentMigration(portable, canAdoptLegacy = { provenLocal }),
                            ),
                        scope = backgroundScope,
                        produceFile = { File(root, "$name.preferences_pb") },
                    ),
                )

                val restored = installation("restored", provenLocal = false)
                val original = installation("original", provenLocal = true)
                assertFalse(restored.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))
                assertTrue(original.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))

                original.setEnabled(FeatureFlag.ACTIVITY_AWARENESS, false)
                assertFalse(original.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))
                assertTrue(portable.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))
            } finally {
                root.deleteRecursively()
            }
        }
}
