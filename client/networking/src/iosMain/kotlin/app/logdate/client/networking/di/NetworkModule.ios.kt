package app.logdate.client.networking.di

import app.logdate.client.networking.CloudBillingApiClient
import app.logdate.client.networking.CloudBillingApiClientContract
import app.logdate.client.networking.CloudTranscriptionApiClient
import app.logdate.client.networking.CloudTranscriptionApiClientContract
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.DefaultDataUsagePolicy
import app.logdate.client.networking.DefaultPlanCatalogClient
import app.logdate.client.networking.DefaultServerDiscoveryClient
import app.logdate.client.networking.DefaultServerHealthChecker
import app.logdate.client.networking.IosNetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.PlanCatalogClient
import app.logdate.client.networking.ServerDiscoveryClient
import app.logdate.client.networking.ServerHealthChecker
import app.logdate.client.networking.httpClient
import org.koin.core.module.Module
import org.koin.dsl.module

actual val networkingModule: Module =
    module {
        single { httpClient }
        single<ServerHealthChecker> { DefaultServerHealthChecker(get()) }
        single<NetworkAvailabilityMonitor> { IosNetworkAvailabilityMonitor() }
        single<DataUsagePolicy> { DefaultDataUsagePolicy(get()) }
        single<ServerDiscoveryClient> { DefaultServerDiscoveryClient(get()) }
        single<PlanCatalogClient> { DefaultPlanCatalogClient(get(), get()) }
        single<CloudBillingApiClientContract> { CloudBillingApiClient(get(), get()) }
        single<CloudTranscriptionApiClientContract> { CloudTranscriptionApiClient(get(), get()) }
    }
