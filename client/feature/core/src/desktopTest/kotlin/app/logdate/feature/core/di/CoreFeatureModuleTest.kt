package app.logdate.feature.core.di

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

class CoreFeatureModuleTest {
    @Test
    fun `coreFeatureModule is defined and includes expected submodules`() {
        assertNotNull(coreFeatureModule)
        assertNotNull(corePlatformModule)
    }
}
