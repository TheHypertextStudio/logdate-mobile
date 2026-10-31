@file:Suppress("ktlint:standard:function-naming")

package app.logdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeWorkspaceContractTest {
    @Test fun rejectsDestinationSearchInsidePanels() {
        val source =
            """
            fun PanelRoot() {
                WorkspacePanel {
                    JournalSearchToolbar()
                    LibraryTopBar()
                    SearchBar()
                }
            }
            """.trimIndent()
        assertEquals(3, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
    }

    @Test fun rejectsWindowPropertiesAndLiteralFraming() {
        val source =
            """
            fun PanelRoot() {
                val size = LocalWindowInfo.current.containerSize
                val config = LocalConfiguration.current
                Column(Modifier.clip(RoundedCornerShape(24.dp))
                    .background(Color.White)) {}
            }
            """.trimIndent()
        assertEquals(4, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
    }

    @Test fun rejectsAliasedOuterSurfacesButAllowsContentCards() {
        val source =
            """
            import androidx.compose.material3.Surface as Frame
            @Composable fun PanelRoot() { Frame { Text("Example") } }
            @Composable fun OtherContent() { Card { Text("Okay") } }
            """.trimIndent()
        assertEquals(1, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
        assertTrue(HomeWorkspaceContract.inspect(source, setOf("OtherContent")).isEmpty())
    }

    @Test fun flagsWindowQueriesEvenWhenNestedInAResponsivePanel() {
        val source = "fun PanelRoot() { WorkspacePanel { val width = currentWindowAdaptiveInfoV2(); Text(width) } }"
        assertEquals(1, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
    }

    @Test fun ignoresStringsAndComments() {
        val source =
            """
            fun PanelRoot() {
                WorkspacePanel { /* currentWindowAdaptiveInfoV2() */ Text("Surface()") }
            }
            """.trimIndent()
        assertTrue(HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).isEmpty())
    }

    @Test fun rejectsWorkspaceSurfaceStylingAndFeatureHingeSplits() {
        val source = "fun PanelRoot() { Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) { FoldableBookLayout() } }"
        assertEquals(2, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
    }

    @Test fun inspectsEveryBranchOfARoot() {
        val source =
            """
            fun PanelRoot() {
                val window = currentWindowAdaptiveInfoV2()
                if (workspaceEnabled) { WorkspacePanel { Text(window) }; return }
                Scaffold {}
            }
            """.trimIndent()
        assertEquals(2, HomeWorkspaceContract.inspect(source, setOf("PanelRoot")).size)
    }

    @Test fun missingScopedRootFailsInsteadOfSilentlyPassing() {
        assertEquals(listOf("MissingRoot|missing-root|1"), HomeWorkspaceContract.inspect("fun RenamedRoot() {}", setOf("MissingRoot")))
    }
}
