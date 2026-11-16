package app.logdate.feature.location.timeline.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LocationHistoryBetaContentTest {
    @Test
    fun betaAloneControlsHumanBrowserRegardlessOfLegacySyncChoice() {
        for (legacy in listOf(false, true)) {
            runDesktopComposeUiTest {
                val flags = Flags(legacy)
                setContent {
                    LocationHistoryBetaContent(flags) { Text("Your day and places") }
                }
                onNodeWithText("Your day and places").assertDoesNotExist()
                onNodeWithText("Legacy browser").assertDoesNotExist()
                runOnIdle { flags.beta.value = true }
                onNodeWithText("Your day and places").assertExists()
                onNodeWithText("Legacy browser").assertDoesNotExist()
                runOnIdle { flags.beta.value = false }
                onNodeWithText("Your day and places").assertDoesNotExist()
                onNodeWithText("Legacy browser").assertDoesNotExist()
                assertEquals(emptyList(), flags.writes)
            }
        }
    }

    @Test
    fun loadingDoesNotExposeHistory() =
        runDesktopComposeUiTest {
            val flags =
                object : FeatureFlagStore {
                    override fun observe(flag: FeatureFlag): Flow<Boolean> = MutableSharedFlow()

                    override suspend fun setEnabled(
                        flag: FeatureFlag,
                        enabled: Boolean,
                    ) = error("Browsing must not write preferences")
                }
            setContent {
                LocationHistoryBetaContent(flags) { Text("Your day and places") }
            }
            onNodeWithText("Legacy browser").assertDoesNotExist()
            onNodeWithText("Your day and places").assertDoesNotExist()
        }

    @Test
    fun disablingDisposesOpenDetailAndReenablingKeepsAvailableHistory() =
        runDesktopComposeUiTest {
            val flags = Flags(true)
            val retainedHistory = mutableListOf("Retained visit")
            setContent {
                LocationHistoryBetaContent(flags) {
                    var opened by remember { mutableStateOf(false) }
                    Text(retainedHistory.single())
                    TextButton(onClick = { opened = true }) { Text("Open visit") }
                    if (opened) Text("Visit detail")
                }
            }
            runOnIdle { flags.beta.value = true }
            onNodeWithText("Retained visit").assertExists()
            onNodeWithText("Open visit").performClick()
            onNodeWithText("Visit detail").assertExists()
            runOnIdle { flags.beta.value = false }
            onNodeWithText("Retained visit").assertDoesNotExist()
            onNodeWithText("Visit detail").assertDoesNotExist()
            runOnIdle { flags.beta.value = true }
            onNodeWithText("Retained visit").assertExists()
            onNodeWithText("Visit detail").assertDoesNotExist()
            assertEquals(listOf("Retained visit"), retainedHistory)
        }

    private class Flags(
        legacyEnabled: Boolean,
    ) : FeatureFlagStore {
        val beta = MutableStateFlow(false)
        private val legacy = MutableStateFlow(legacyEnabled)
        val writes = mutableListOf<FeatureFlag>()

        override fun observe(flag: FeatureFlag): Flow<Boolean> = if (flag == FeatureFlag.PLACES_BETA) beta else legacy

        override suspend fun setEnabled(
            flag: FeatureFlag,
            enabled: Boolean,
        ) {
            writes += flag
        }
    }
}
