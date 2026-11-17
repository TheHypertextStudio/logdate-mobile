@file:Suppress("TooGenericExceptionCaught")

package app.logdate.client.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.logdate.client.billing.model.LogDateBackupPlanOption
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.queryProductDetails
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"

class PlayStoreSubscriptionBiller
    @Inject
    constructor(
        private val context: Context,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : SubscriptionBiller {
        /**
         * Play Billing must be launched from a foreground Activity. The injected context is the
         * application, so it is tracked separately rather than unwrapped.
         */
        private val foregroundActivityTracker = trackerFor(context)

        override suspend fun purchasePlan(plan: LogDateBackupPlanOption) =
            withContext(ioDispatcher) {
                if (plan == LogDateBackupPlanOption.BASIC) return@withContext
                ensureConnected()
                val productDetails =
                    querySubscriptionProductDetails().firstOrNull { it.productId == plan.sku }
                        ?: error("Play Billing product '${plan.sku}' is not configured.")
                val offerToken =
                    productDetails.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.offerToken
                        ?: error("Play Billing product '${plan.sku}' has no subscription offer.")
                val activity =
                    foregroundActivityTracker?.current()
                        ?: context.unwrapActivity()
                        ?: error("A foreground Activity is required to launch Play Billing.")
                val billingResult =
                    billingClient.launchBillingFlow(
                        activity,
                        BillingFlowParams
                            .newBuilder()
                            .setProductDetailsParamsList(
                                listOf(
                                    BillingFlowParams.ProductDetailsParams
                                        .newBuilder()
                                        .setProductDetails(productDetails)
                                        .setOfferToken(offerToken)
                                        .build(),
                                ),
                            ).build(),
                    )
                require(billingResult.responseCode == BillingResponseCode.OK) {
                    "Play Billing failed to launch: ${billingResult.debugMessage}"
                }
            }

        override suspend fun cancelPlan() {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }

        private val purchasesUpdatedListener: PurchasesUpdatedListener =
            PurchasesUpdatedListener { billingResult, purchases ->
                if (billingResult.responseCode != BillingResponseCode.OK) {
                    Napier.w("Play Billing purchase update failed: ${billingResult.debugMessage}")
                    return@PurchasesUpdatedListener
                }
                purchases.orEmpty().forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                        billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams
                                .newBuilder()
                                .setPurchaseToken(purchase.purchaseToken)
                                .build(),
                            AcknowledgePurchaseResponseListener { result ->
                                if (result.responseCode != BillingResponseCode.OK) {
                                    Napier.w("Failed to acknowledge Play purchase: ${result.debugMessage}")
                                }
                            },
                        )
                    }
                }
            }

        private val billingClient =
            BillingClient
                .newBuilder(context)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                ).build()

        private suspend fun ensureConnected() {
            if (billingClient.isReady) return
            suspendCancellableCoroutine { continuation ->
                billingClient.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            if (billingResult.responseCode == BillingResponseCode.OK) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(
                                    IllegalStateException("Play Billing unavailable: ${billingResult.debugMessage}"),
                                )
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            Napier.w("Play Billing service disconnected")
                        }
                    },
                )
            }
        }

        private suspend fun querySubscriptionProductDetails(): List<ProductDetails> {
            val products =
                LogDateBackupPlanOption.entries
                    .filterNot { it == LogDateBackupPlanOption.BASIC }
                    .map { plan ->
                        QueryProductDetailsParams.Product
                            .newBuilder()
                            .setProductId(plan.sku)
                            .setProductType(ProductType.SUBS)
                            .build()
                    }
            val result =
                billingClient.queryProductDetails(
                    QueryProductDetailsParams
                        .newBuilder()
                        .setProductList(products)
                        .build(),
                )
            if (result.billingResult.responseCode != BillingResponseCode.OK) {
                throw IllegalStateException(
                    "Failed to query Play Billing products: ${result.billingResult.debugMessage}",
                )
            }
            return result.productDetailsList.orEmpty()
        }
    }
