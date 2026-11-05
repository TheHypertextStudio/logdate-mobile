package app.logdate.client.e2e

import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.data.maintenance.DataIntegrityService
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.dao.maintenance.IntegrityDao
import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.domain.quota.ObserveCloudQuotaUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.settings.ui.CloudArchivePhase
import app.logdate.feature.core.settings.ui.CloudArchiveStatus
import app.logdate.feature.core.settings.ui.CloudArchiveStatusSource
import app.logdate.feature.core.settings.ui.DataSettingsViewModel
import app.logdate.feature.core.settings.ui.SyncSettingsScreen
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Drives the real Sync screen and its production feedback effect using disposable account data. */
@RunWith(AndroidJUnit4::class)
class SettingsFeedbackLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun emulatorOnly() {
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an Android emulator" }
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun stoppedSnackbarRetainsItsIdAndDismissalDoesNotLoseTheNextResult() {
        val fixture = showScreen(signedIn = true, initialState = Lifecycle.State.STARTED)
        try {
            compose.runOnUiThread { fixture.model.syncNow() }
            compose.waitUntil(timeoutMillis = 5_000) { fixture.model.syncFeedbackDelivery.value != null }
            settleFrames()
            compose.onNodeWithText(STARTED_MESSAGE).assertIsDisplayed()
            val first = requireNotNull(fixture.model.syncFeedbackDelivery.value)

            compose.runOnUiThread { fixture.owner.lifecycle.currentState = Lifecycle.State.CREATED }
            settleFrames()
            compose.onNodeWithText(STARTED_MESSAGE).assertDoesNotExist()
            assertEquals(
                first.id,
                fixture.model.syncFeedbackDelivery.value
                    ?.id,
            )

            // A second scheduler result arrives while the first result is waiting for its screen.
            compose.runOnUiThread { fixture.model.syncNow() }
            settleFrames()
            assertEquals(
                first.id,
                fixture.model.syncFeedbackDelivery.value
                    ?.id,
            )
            compose.onNodeWithText(STARTED_MESSAGE).assertDoesNotExist()

            compose.runOnUiThread { fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED }
            settleFrames()
            compose.onNodeWithText(STARTED_MESSAGE).assertIsDisplayed()
            assertEquals(
                first.id,
                fixture.model.syncFeedbackDelivery.value
                    ?.id,
            )

            dismissSnackbar()
            compose.waitUntil(timeoutMillis = 5_000) {
                fixture.model.syncFeedbackDelivery.value
                    ?.id
                    ?.let { it != first.id } == true
            }
            settleFrames()
            val second = requireNotNull(fixture.model.syncFeedbackDelivery.value)
            assertNotEquals(first.id, second.id)
            compose.onNodeWithText(STARTED_MESSAGE).assertIsDisplayed()

            dismissSnackbar()
            compose.waitUntil(timeoutMillis = 5_000) { fixture.model.syncFeedbackDelivery.value == null }
            settleFrames()
            compose.onNodeWithText(STARTED_MESSAGE).assertDoesNotExist()
            compose.runOnUiThread {
                fixture.owner.lifecycle.currentState = Lifecycle.State.CREATED
                fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED
            }
            settleFrames()
            assertNull(fixture.model.syncFeedbackDelivery.value)
            compose.onNodeWithText(STARTED_MESSAGE).assertDoesNotExist()
        } finally {
            close(fixture)
        }
    }

    @Test
    fun resultProducedWhileInitiallyStoppedWaitsForStartAndActionAcknowledgesItOnce() {
        var signInActions = 0
        val fixture = showScreen(signedIn = false, initialState = Lifecycle.State.CREATED) { signInActions++ }
        try {
            compose.runOnUiThread { fixture.model.syncNow() }
            compose.waitUntil(timeoutMillis = 5_000) { fixture.model.syncFeedbackDelivery.value != null }
            val pending = requireNotNull(fixture.model.syncFeedbackDelivery.value)
            settleFrames()
            compose.onNodeWithText(NEEDS_ACCOUNT_MESSAGE).assertDoesNotExist()
            assertEquals(0, signInActions)

            compose.runOnUiThread { fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED }
            settleFrames()
            compose.onNodeWithText(NEEDS_ACCOUNT_MESSAGE).assertIsDisplayed()
            assertEquals(
                pending.id,
                fixture.model.syncFeedbackDelivery.value
                    ?.id,
            )
            compose
                .onNode(hasText("Sign in") and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)))
                .performClick()
            compose.waitUntil(timeoutMillis = 5_000) { fixture.model.syncFeedbackDelivery.value == null }
            settleFrames()
            assertEquals(1, signInActions)
            compose.onNodeWithText(NEEDS_ACCOUNT_MESSAGE).assertDoesNotExist()

            compose.runOnUiThread {
                fixture.owner.lifecycle.currentState = Lifecycle.State.CREATED
                fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED
            }
            settleFrames()
            assertEquals(1, signInActions)
            assertNull(fixture.model.syncFeedbackDelivery.value)
            compose.onNodeWithText(NEEDS_ACCOUNT_MESSAGE).assertDoesNotExist()
        } finally {
            close(fixture)
        }
    }

    private fun showScreen(
        signedIn: Boolean,
        initialState: Lifecycle.State,
        onSignIn: () -> Unit = {},
    ): FeedbackFixture {
        lateinit var fixture: FeedbackFixture
        compose.runOnUiThread { fixture = FeedbackFixture(signedIn, initialState) }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides fixture.owner) {
                LogDateTheme(darkTheme = false) {
                    SyncSettingsScreen(
                        onBack = {},
                        onNavigateToSignIn = onSignIn,
                        viewModel = fixture.model,
                        mergeJournals = fixture.mergeJournals,
                    )
                }
            }
        }
        settleFrames()
        return fixture
    }

    private fun settleFrames() {
        // Advance entrance/exit frames without auto-advancing through the short snackbar timeout.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun dismissSnackbar() {
        compose
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> dismiss() }
    }

    private fun close(fixture: FeedbackFixture) {
        compose.runOnUiThread {
            fixture.owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            fixture.model.viewModelScope.cancel()
        }
    }

    private class FeedbackLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class FeedbackFixture(
        signedIn: Boolean,
        initialState: Lifecycle.State,
    ) {
        val owner = FeedbackLifecycle().also { it.lifecycle.currentState = initialState }
        private val session = if (signedIn) UserSession("fixture-access", "fixture-refresh", "fixture-account") else null
        private val quota = CloudStorageQuota(100, 25, emptyList())
        private val configuration = DefaultLogDateConfigRepository()
        private val quotaManager =
            feedbackDependency<CloudQuotaManager> { method ->
                when (method) {
                    "observeQuota" -> flowOf(quota)
                    "observeQuotaSnapshots" -> flowOf(CloudQuotaSnapshot.Available(session?.accountId, quota))
                    "getCurrentQuota" -> quota
                    else -> error("Unexpected quota operation: $method")
                }
            }
        private val syncManager =
            feedbackDependency<SyncManager> { method ->
                when (method) {
                    "getSyncStatusFlow" -> MutableStateFlow(SyncStatus(true, null, 0, false, false))
                    "requestBackup" -> Unit
                    else -> error("Unexpected sync operation: $method")
                }
            }
        private val sessions =
            object : SessionStorage {
                override fun getSession(): UserSession? = session

                override fun getSessionFlow(): Flow<UserSession?> = MutableStateFlow(session)

                override suspend fun hasValidSession() = session != null

                override suspend fun saveSession(session: UserSession) = error("Unexpected session write")

                override suspend fun clearSession() = error("Unexpected session removal")
            }
        private val archive =
            object : CloudArchiveStatusSource {
                override fun observe(session: UserSession) = flowOf(CloudArchiveStatus(CloudArchivePhase.COMPLETE))

                override suspend fun requestBackup() = error("Unexpected archive request")
            }
        private val preferences =
            LogdatePreferencesDataSource(
                object : DataStore<Preferences> {
                    private val values = MutableStateFlow(emptyPreferences())
                    override val data: Flow<Preferences> = values

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        val updated = transform(values.value)
                        values.value = updated
                        return updated
                    }
                },
            )
        private val integrity =
            DataIntegrityService(
                unusedFeedbackDependency<IntegrityDao>(),
                unusedFeedbackDependency<SyncMetadataDao>(),
                unusedFeedbackDependency<JournalContentDao>(),
                configuration,
                unusedFeedbackDependency<CanonicalOwnerProvider>(),
                unusedFeedbackDependency<DeviceIdProvider>(),
            )
        val mergeJournals =
            MergeJournalsUseCase(
                feedbackDependency<JournalRepository> { method ->
                    when (method) {
                        "observeJournalMergeIssues" -> flowOf(emptyList<JournalMergeOperation>())
                        else -> error("Unexpected journal operation: $method")
                    }
                },
                unusedFeedbackDependency<JournalContentRepository>(),
                syncManager,
            )
        val model =
            DataSettingsViewModel(
                ObserveCloudQuotaUseCase(quotaManager),
                syncManager,
                sessions,
                preferences,
                configuration,
                integrity,
                archive,
            )
    }

    private companion object {
        const val STARTED_MESSAGE = "Backup requested. Waiting to start."
        const val NEEDS_ACCOUNT_MESSAGE = "Sign in to start backing up"
    }
}

private inline fun <reified T> unusedFeedbackDependency(): T =
    feedbackDependency { method -> error("Unexpected ${T::class.java.simpleName} operation: $method") }

private inline fun <reified T> feedbackDependency(crossinline call: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "Feedback fixture ${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> call(method.name)
        }
    } as T
