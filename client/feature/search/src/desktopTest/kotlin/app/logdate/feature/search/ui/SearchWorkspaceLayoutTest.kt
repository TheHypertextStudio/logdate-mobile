@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SearchWorkspaceLayoutTest {
    @Test
    fun `a wide window places all three panels`() =
        runComposeUiTest {
            setContent { LogDateTheme { Layout(1180, 740) } }

            onNodeWithTag("narrowing_Panel").assertExists()
            onNodeWithTag("results").assertExists()
            onNodeWithTag("preview").assertExists()
            onNodeWithTag("narrowing_Row").assertDoesNotExist()
        }

    @Test
    fun `a medium window places results and a preview with the narrowing row above the results`() =
        runComposeUiTest {
            setContent { LogDateTheme { Layout(900, 620) } }

            onNodeWithTag("narrowing_Row").assertExists()
            onNodeWithTag("results").assertExists()
            onNodeWithTag("preview").assertExists()
            onNodeWithTag("narrowing_Panel").assertDoesNotExist()
        }

    @Test
    fun `a narrow window places only the narrowing row and the results`() =
        runComposeUiTest {
            setContent { LogDateTheme { Layout(400, 800) } }

            onNodeWithTag("narrowing_Row").assertExists()
            onNodeWithTag("results").assertExists()
            onNodeWithTag("preview").assertDoesNotExist()
            onNodeWithTag("narrowing_Panel").assertDoesNotExist()
        }

    @Test
    fun `the results learn which panels are showing`() =
        runComposeUiTest {
            val seen = mutableListOf<SearchPanels>()
            setContent {
                LogDateTheme {
                    Box(Modifier.requiredSize(1180.dp, 740.dp)) {
                        SearchWorkspaceLayout(
                            narrowing = { Text("narrowing", Modifier.testTag("narrowing_$it")) },
                            results = {
                                seen += it
                                Text("results", Modifier.testTag("results"))
                            },
                            preview = { Text("preview", Modifier.testTag("preview")) },
                        )
                    }
                }
            }

            onNodeWithTag("results").assertExists()
            assertEquals(SearchPanels.NarrowingResultsAndPreview, seen.last())
        }

    @Composable
    private fun Layout(
        width: Int,
        height: Int,
    ) {
        Box(Modifier.requiredSize(width.dp, height.dp)) {
            SearchWorkspaceLayout(
                narrowing = { Text("narrowing", Modifier.testTag("narrowing_$it")) },
                results = { Text("results", Modifier.testTag("results")) },
                preview = { Text("preview", Modifier.testTag("preview")) },
            )
        }
    }
}
