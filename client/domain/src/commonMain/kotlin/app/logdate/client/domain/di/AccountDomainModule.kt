package app.logdate.client.domain.di

import app.logdate.client.domain.account.AuthenticateWithPasskeyUseCase
import app.logdate.client.domain.account.BackfillLocalDataUseCase
import app.logdate.client.domain.account.BackfilledAccountTracker
import app.logdate.client.domain.account.CheckUsernameAvailabilityUseCase
import app.logdate.client.domain.account.CloudPlanBilling
import app.logdate.client.domain.account.CreatePasskeyAccountUseCase
import app.logdate.client.domain.account.CreateRemoteAccountUseCase
import app.logdate.client.domain.account.EmailVerificationAvailability
import app.logdate.client.domain.account.EnqueueAllLocalDataUseCase
import app.logdate.client.domain.account.GetAccountSetupDataUseCase
import app.logdate.client.domain.account.GetAvailablePlansUseCase
import app.logdate.client.domain.account.GetCurrentAccountUseCase
import app.logdate.client.domain.account.GetCurrentEntitlementUseCase
import app.logdate.client.domain.account.HasLogDateCloudAccountUseCase
import app.logdate.client.domain.account.PreferencesBackfilledAccountTracker
import app.logdate.client.domain.account.ServerCloudPlanBilling
import app.logdate.client.domain.account.SignInWithGoogleUseCase
import app.logdate.client.domain.account.SignUpWithGoogleUseCase
import app.logdate.client.domain.account.TriggerInitialSyncUseCase
import app.logdate.client.domain.account.TryRestoreSignInUseCase
import app.logdate.client.domain.account.VerifyEmailUseCase
import app.logdate.client.domain.user.GetUserIdUseCase
import app.logdate.client.repository.account.PasskeyAccountRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Module for account-related domain use cases.
 * Separated to avoid circular dependencies with data modules.
 */
val accountDomainModule: Module =
    module {
        // Account
        factory { CreatePasskeyAccountUseCase(get()) }
        factory { GetCurrentAccountUseCase(get()) }
        factory { HasLogDateCloudAccountUseCase(get()) }
        factory { GetAccountSetupDataUseCase(get()) }
        factory { CreateRemoteAccountUseCase(get()) }
        factory { CheckUsernameAvailabilityUseCase(get()) }
        factory { AuthenticateWithPasskeyUseCase(get()) }
        factory { SignInWithGoogleUseCase(get()) }
        factory { SignUpWithGoogleUseCase(get()) }
        factory { TryRestoreSignInUseCase(get()) }
        factory { TriggerInitialSyncUseCase(syncManager = get()) }
        factory<BackfilledAccountTracker> { PreferencesBackfilledAccountTracker(preferences = get()) }
        factory {
            EnqueueAllLocalDataUseCase(
                journalRepository = get(),
                journalNotesRepository = get(),
                syncMetadataService = get(),
            )
        }
        factory { BackfillLocalDataUseCase(enqueueAllLocalData = get(), tracker = get()) }
        factory { GetCurrentEntitlementUseCase(sessionStorage = get(), apiClient = get()) }
        factory { GetAvailablePlansUseCase(planCatalogClient = get()) }
        factory<CloudPlanBilling> {
            val accounts = get<PasskeyAccountRepository>()
            ServerCloudPlanBilling(
                sessionStorage = get(),
                billing = get(),
                catalog = get(),
                refreshSession = { accounts.refreshAuthentication().isSuccess },
            )
        }
        factory { VerifyEmailUseCase(sessionStorage = get(), manager = get()) }
        factory { EmailVerificationAvailability(manager = get(), getCurrentEntitlement = get()) }

        factory { GetUserIdUseCase(get()) }
    }
