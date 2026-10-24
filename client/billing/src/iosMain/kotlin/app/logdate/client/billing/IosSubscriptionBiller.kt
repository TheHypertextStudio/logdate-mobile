@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.client.billing

import app.logdate.client.billing.model.LogDateBackupPlanOption
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.StoreKit.SKPayment
import platform.StoreKit.SKPaymentQueue
import platform.StoreKit.SKPaymentTransaction
import platform.StoreKit.SKPaymentTransactionObserverProtocol
import platform.StoreKit.SKPaymentTransactionState
import platform.StoreKit.SKProduct
import platform.StoreKit.SKProductsRequest
import platform.StoreKit.SKProductsRequestDelegateProtocol
import platform.StoreKit.SKProductsResponse
import platform.UIKit.UIApplication
import platform.darwin.NSObject

private const val MANAGE_SUBSCRIPTIONS_URL = "https://apps.apple.com/account/subscriptions"

/**
 * iOS [SubscriptionBiller] backed by the Obj-C StoreKit API (`SKProductsRequest`,
 * `SKPaymentQueue`, `SKPaymentTransactionObserver`).
 *
 * Auto-renewable subscription products are configured in App Store Connect with the SKUs declared
 * in [LogDateBackupPlanOption]. Until those products are configured, [SKProductsRequest] reports
 * the SKUs as invalid and [purchasePlan] surfaces a clear runtime error rather than silently
 * succeeding.
 *
 * Cancellation is intentionally redirected to Apple's manage-subscriptions surface — iOS does not
 * permit programmatic cancellation, so [cancelPlan] opens the system-managed URL instead.
 */
class IosSubscriptionBiller : SubscriptionBiller {
    private val _isSubscribed = MutableStateFlow(false)
    private val _availablePlans = MutableStateFlow(LogDateBackupPlanOption.entries.toList())
    private val loadedProducts = MutableStateFlow<Map<String, SKProduct>>(emptyMap())

    override val isSubscribed: Flow<Boolean> = _isSubscribed.asStateFlow()
    override val availablePlans: Flow<List<LogDateBackupPlanOption>> = _availablePlans.asStateFlow()

    private val storeKitDelegate = StoreKitDelegate()

    init {
        SKPaymentQueue.defaultQueue().addTransactionObserver(storeKitDelegate)
        loadProducts()
    }

    override suspend fun purchasePlan(plan: LogDateBackupPlanOption) {
        if (plan.sku.isBlank() || plan == LogDateBackupPlanOption.BASIC) {
            // Basic is the free tier — no StoreKit transaction needed.
            _isSubscribed.value = false
            return
        }
        val product =
            loadedProducts.value[plan.sku]
                ?: error(
                    "StoreKit product '${plan.sku}' not loaded — verify the SKU exists in " +
                        "App Store Connect and the device's Apple ID has access to the bundle.",
                )
        val payment = SKPayment.paymentWithProduct(product)
        SKPaymentQueue.defaultQueue().addPayment(payment)
    }

    override suspend fun cancelPlan() {
        val url = NSURL.URLWithString(MANAGE_SUBSCRIPTIONS_URL) ?: return
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }

    private fun loadProducts() {
        val skus =
            LogDateBackupPlanOption.entries
                .asSequence()
                .filter { it != LogDateBackupPlanOption.BASIC }
                .map { it.sku }
                .toSet()
        if (skus.isEmpty()) return
        val request = SKProductsRequest(productIdentifiers = skus)
        request.delegate = storeKitDelegate
        request.start()
    }

    private inner class StoreKitDelegate :
        NSObject(),
        SKProductsRequestDelegateProtocol,
        SKPaymentTransactionObserverProtocol {
        override fun productsRequest(
            request: SKProductsRequest,
            didReceiveResponse: SKProductsResponse,
        ) {
            @Suppress("UNCHECKED_CAST")
            val products = (didReceiveResponse.products as? List<SKProduct>).orEmpty()
            loadedProducts.value = products.associateBy { it.productIdentifier }
            val invalid = didReceiveResponse.invalidProductIdentifiers
            if (invalid.isNotEmpty()) {
                Napier.w("StoreKit reported invalid product IDs: $invalid")
            }
        }

        override fun paymentQueue(
            queue: SKPaymentQueue,
            updatedTransactions: List<*>,
        ) {
            @Suppress("UNCHECKED_CAST")
            val transactions = updatedTransactions as List<SKPaymentTransaction>
            for (transaction in transactions) {
                when (transaction.transactionState) {
                    SKPaymentTransactionState.SKPaymentTransactionStatePurchased,
                    SKPaymentTransactionState.SKPaymentTransactionStateRestored,
                    -> {
                        _isSubscribed.value = true
                        queue.finishTransaction(transaction)
                    }

                    SKPaymentTransactionState.SKPaymentTransactionStateFailed -> {
                        Napier.w(
                            "StoreKit purchase failed: ${transaction.error?.localizedDescription}",
                        )
                        queue.finishTransaction(transaction)
                    }

                    else -> {
                        Unit
                    } // purchasing / deferred — wait for the next callback
                }
            }
        }
    }
}
