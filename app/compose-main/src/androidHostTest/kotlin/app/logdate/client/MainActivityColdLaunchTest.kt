package app.logdate.client

import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Android creates the launch activity while the application's startup is still waiting to install
 * Koin, so constructing [MainActivity] must not resolve any dependency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MainActivityColdLaunchTest {
    @Test
    fun `activity is created before dependencies are installed`() {
        assertNull(GlobalContext.getOrNull())

        val controller = Robolectric.buildActivity(MainActivity::class.java)

        assertNotNull(controller.get())
    }
}
