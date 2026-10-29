package app.logdate.client.data.di

import app.logdate.client.data.account.DefaultAccountIdentityRepository
import app.logdate.client.data.account.DefaultAccountRepository
import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.DefaultServerScopedAccounts
import app.logdate.client.data.account.HistoryOwnerAdoption
import app.logdate.client.data.account.PasskeyCredentialCodec
import app.logdate.client.data.account.PasskeyIdentityStepUp
import app.logdate.client.data.account.RestoreCredentialLifecycle
import app.logdate.client.data.account.RestoreCredentialRenewal
import app.logdate.client.data.account.ServerScopedAccounts
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.identity.userVisibleDeviceName
import app.logdate.client.networking.IdentityApiClientContract
import app.logdate.client.networking.PasskeyApiClient
import app.logdate.client.networking.httpClient
import app.logdate.client.repository.account.AccountIdentityRepository
import app.logdate.client.repository.account.AccountRepository
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.account.PasskeyConfirmations
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.shared.config.DefaultLogDateConfigRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val androidAccountModule =
    module {
        includes(atprotoConnectionModule)
        // Account
        single<AccountRepository> {
            DefaultAccountRepository(get()) {
                get<SessionStorage>().getSession()?.accessToken
            }
        }
        single {
            RestoreCredentialLifecycle(
                get(),
                get(),
                get(),
                app.logdate.client.datastore
                    .NativeRestorePolicy(get<app.logdate.client.datastore.KeyValueStorage>()),
                get(),
                get(),
                platformAccounts = get(),
            )
        }
        single<PasskeyAccountRepository> {
            val context = androidContext()
            DefaultPasskeyAccountRepository(
                get(),
                get(),
                get<RestoreCredentialLifecycle>(),
                get(),
                get(),
                get(),
                get(),
                adoptCanonicalOwner = { get<HistoryOwnerAdoption>().adopt(it) },
                hasLocalData = {
                    val journals = get<JournalRepository>()
                    val notes = get<JournalNotesRepository>()
                    journals.allJournalsObserved.first().isNotEmpty() ||
                        notes.allNotesObserved.first().isNotEmpty() ||
                        journals.getAllDrafts().isNotEmpty() ||
                        notes.getAllJournalNoteLinks().isNotEmpty() ||
                        get<HistoryOwnerAdoption>().hasLocalHistory()
                },
                googleSignInManager = get(),
                serverClientId = DefaultLogDateConfigRepository.GOOGLE_SERVER_CLIENT_ID,
                deviceName = { context.userVisibleDeviceName() },
                passkeyConfirmations = get(),
                accountKeyUnlockCoordinator = get(),
                beforeSignOut = {
                    getOrNull<AtprotoConnectionRepository>()?.let { connection ->
                        try {
                            connection.cancelPending()
                        } catch (
                            canceled: CancellationException,
                        ) {
                            throw canceled
                        } catch (
                            _: Exception,
                        ) {
                            Napier.w("Could not clear the pending account connection request")
                        } finally {
                            connection.invalidate()
                        }
                    }
                    getOrNull<app.logdate.client.sync.peer.removal.WatchRemovalAccess>()?.removeAll() ?: true
                },
                restoreCredentialRenewal =
                    RestoreCredentialRenewal(
                        get(),
                    ) { pinned -> PasskeyApiClient(httpClient, pinned) },
            )
        }
        single<ServerScopedAccounts> {
            val context = androidContext()
            DefaultServerScopedAccounts(
                httpClient = httpClient,
                vault = get(),
                passkeyManager = get(),
                platformAccountManager = get(),
                canonicalOwnerProvider = get(),
                // Only a moving account is opened this way, and it always has this device's entries.
                hasLocalData = { true },
                adoptCanonicalOwner = { owner, origin -> get<HistoryOwnerAdoption>().adoptOnOrigin(owner, origin) },
                deviceName = { context.userVisibleDeviceName() },
            )
        }
        single { PasskeyConfirmations() }
        single<AccountIdentityRepository> {
            DefaultAccountIdentityRepository(
                apiClient = get(),
                sessionStorage = get(),
                plcRecoveryKeyManager = get(),
                stepUp =
                    PasskeyIdentityStepUp(
                        beginStepUp = get<IdentityApiClientContract>()::beginRecoveryKeyStepUp,
                        passkeyManager = get(),
                        credentialCodec = PasskeyCredentialCodec(Json { ignoreUnknownKeys = true }),
                    ),
                passkeyConfirmations = get(),
                json = get(),
            )
        }
    }
