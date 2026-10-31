package app.logdate.feature.core.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.repository.assistants.AssistantCapability.READ_MEMORIES
import app.logdate.client.repository.assistants.AssistantCapability.READ_PEOPLE
import app.logdate.client.repository.assistants.AssistantCapability.READ_PLACES
import app.logdate.client.repository.assistants.AssistantCapability.START_CAPTURE
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.uuid.Uuid

/** Full-length captures of fictional populated states, scrolled through every section; nothing is granted. */
@OptIn(ExperimentalTestApi::class)
class ConnectedAssistantRenderTest {
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val notes = Uuid.parse("00000000-0000-4000-8000-000000000002")
    private val journals = listOf(AssistantJournalOption(travel, "Travel"), AssistantJournalOption(notes, "Notes"))
    private val everything = setOf(READ_MEMORIES, READ_PLACES, READ_PEOPLE, START_CAPTURE)
    private val access = AssistantAccessSelection(setOf(READ_MEMORIES, READ_PLACES), setOf(travel, notes), includeUnfiled = false)
    private val claude = ConnectedAssistantSummary("claude-1", "Claude", access.capabilities, "October 30", "today")
    private val viewports =
        listOf(
            Viewport("compact", 400, 760),
            Viewport("wide-dark", 960, 760, dark = true),
            Viewport("large-rtl", 360, 640, fontScale = 2f, rtl = true),
        )

    @Test
    fun `connect sheet keeps every choice reachable with actions pinned`() =
        viewports.forEach { viewport ->
            render(viewport, content = {
                val request = AssistantConnectRequest("Claude", "Alex’s Mac", everything)
                AssistantConnectContent(request, journals, access, false, false, {}, {}, {})
            }, steps = {
                save("connect-${viewport.name}-top")
                scrollTo("Start a voice note or entry", "connect-${viewport.name}-do")
                scrollTo("Can’t write or change your entries", "connect-${viewport.name}-facts")
            })
        }

    @Test
    fun `assistants settings show connected assistants, availability and how to connect`() =
        viewports.forEach { viewport ->
            render(viewport, content = {
                ConnectedAssistantsContent(listOf(claude), "Alex’s Mac", false, {}, {}, {}, {})
            }, steps = {
                save("settings-${viewport.name}-top")
                scrollTo("Copy connector link", "settings-${viewport.name}-connect")
            })
        }

    @Test
    fun `an empty assistants page leads with the invitation`() =
        viewports.forEach { viewport ->
            render(viewport, content = {
                ConnectedAssistantsContent(emptyList(), "Alex’s Mac", null, {}, {}, {}, {})
            }, steps = { save("settings-empty-${viewport.name}-top") })
        }

    @Test
    fun `an assistant's page shows activity, access and disconnect`() =
        viewports.forEach { viewport ->
            render(viewport, content = {
                ConnectedAssistantContent(
                    claude,
                    everything,
                    journals,
                    access,
                    listOf(
                        AssistantActivity(READ_MEMORIES, "Searched “canal walk”", "2 hours ago"),
                        AssistantActivity(READ_PLACES, "Looked at October places", "Yesterday"),
                    ),
                    false,
                    {},
                    {},
                    {},
                )
            }, steps = {
                save("assistant-${viewport.name}-top")
                scrollTo("People", "assistant-${viewport.name}-access")
                scrollTo("Disconnect", "assistant-${viewport.name}-disconnect")
            })
        }

    private fun render(
        viewport: Viewport,
        content: @Composable () -> Unit,
        steps: DesktopComposeUiTest.() -> Unit,
    ) {
        runDesktopComposeUiTest(
            width = viewport.width,
            height = viewport.height,
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        ) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, viewport.fontScale),
                    LocalLayoutDirection provides if (viewport.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    LogDateTheme(dynamicColor = false, darkTheme = viewport.dark) { content() }
                }
            }
            steps()
        }
    }

    private fun DesktopComposeUiTest.scrollTo(
        label: String,
        name: String,
    ) {
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        onNodeWithText(label).assertIsDisplayed()
        save(name)
    }

    private fun DesktopComposeUiTest.save(name: String) {
        val bitmap = onRoot().captureToImage()
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val root = System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it, "renders") } ?: File("build/reports/assistant-renders")
        root.mkdirs()
        ImageIO.write(image, "png", File(root, "$name.png"))
    }

    private data class Viewport(
        val name: String,
        val width: Int,
        val height: Int,
        val fontScale: Float = 1f,
        val dark: Boolean = false,
        val rtl: Boolean = false,
    )
}
