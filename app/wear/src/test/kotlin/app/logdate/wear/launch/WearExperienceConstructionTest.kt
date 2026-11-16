package app.logdate.wear.launch

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WearExperienceConstructionTest {
    @Test fun `provider failure after successful storage startup still resolves local navigation`() =
        runTest {
            val actual = runCatching { resolveWearExperience(null) { error("Flag provider construction unavailable") } }.getOrNull()
            assertEquals(WearExperience.V1, actual)
        }

    @Test fun `a saved choice never constructs a failed provider`() =
        runTest {
            assertEquals(WearExperience.V2, resolveWearExperience(WearExperience.V2) { error("Must not construct") })
        }

    @Test fun `activity cancellation is preserved rather than opening another experience`() =
        runTest {
            assertFailsWith<CancellationException> { resolveWearExperience(null) { throw CancellationException("Activity stopped") } }
        }

    @Test fun `a healthy provider keeps the selected watch experience`() =
        runTest {
            val flags =
                object : FeatureFlagStore {
                    override fun observe(flag: FeatureFlag) = flowOf(true)

                    override suspend fun setEnabled(
                        flag: FeatureFlag,
                        enabled: Boolean,
                    ) = Unit
                }
            assertEquals(WearExperience.V2, resolveWearExperience(null) { WearExperienceGate(flags) })
        }
}
