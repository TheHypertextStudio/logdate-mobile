# `:client:billing`

This module lets the app offer, start and manage a LogDate Cloud backup subscription through each platform's own store. It defines one interface, `SubscriptionBiller`, and one implementation per platform: Google Play Billing on Android, StoreKit on iOS, and a browser hand-off on desktop. The Android app, the onboarding feature and the screenshot scenes use it to show plans and begin a purchase.

## What it contains

| Type                          | Package                                | What it does                                                                                                                                                                                                          |
| ----------------------------- | -------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SubscriptionBiller`          | `app.logdate.client.billing`           | The interface the app codes against. It exposes `isSubscribed` and `availablePlans` as flows, plus `purchasePlan` and `cancelPlan`.                                                                                   |
| `LogDateBackupPlanOption`     | `app.logdate.client.billing.model`     | An enum with `BASIC` and `STANDARD`. Each entry carries the store product identifier in `sku`. The plan name, description and price are words for the screen, so the UI maps each option to its own string resources. |
| `PlayStoreSubscriptionBiller` | `app.logdate.client.billing` (Android) | Connects a Play `BillingClient`, lists the subscription products that exist in Play, launches the purchase flow, acknowledges purchases, and reports a subscription when Play returns a purchased subscription.       |
| `ForegroundActivityTracker`   | `app.logdate.client.billing` (Android) | Remembers the resumed `Activity`. Play Billing has to be launched from an Activity, and the biller is created with the application context.                                                                           |
| `IosSubscriptionBiller`       | `app.logdate.client.billing` (iOS)     | Loads StoreKit products for the non-free plans, adds payments to the `SKPaymentQueue`, and marks the user subscribed when a transaction is purchased or restored.                                                     |
| `DesktopSubscriptionBiller`   | `app.logdate.client.billing` (JVM)     | Opens the checkout page in the system browser for a paid plan and the account management page for cancellation.                                                                                                       |
| `billingModule`               | `app.logdate.client.di`                | An `expect` Koin module. Each platform's `actual` binds `SubscriptionBiller` to that platform's implementation.                                                                                                       |

`BASIC` is the free tier on every platform: `purchasePlan(BASIC)` starts no store transaction. On Android, `availablePlans` always includes `BASIC` and includes `STANDARD` only when its SKU is configured in Play.

Cancellation is handled by the store, not by the app. `cancelPlan` opens the Play subscriptions page on Android, Apple's subscription management page on iOS, and the management URL on desktop.

## Desktop configuration

`DesktopSubscriptionBiller` builds the checkout address from the `LOGDATE_BILLING_CHECKOUT_URL` environment variable and appends `?plan=<sku>`. It opens the address from `LOGDATE_BILLING_MANAGEMENT_URL` for cancellation. When a variable is unset or blank, it uses `https://logdate.app/billing/checkout` and `https://logdate.app/account/billing`.

## How it fits with other modules

The module has no dependencies on other LogDate modules. It uses Kotlin coroutines, Napier and Koin in common code, plus the Play Billing KTX library and Koin Android on Android. The Android app (`app/android-main`), `:client:feature:onboarding` and `:client:screenshot-scenes` depend on it.

## Platforms

Android, JVM (desktop) and iOS (`iosArm64` and `iosSimulatorArm64`, added by the shared KMP library convention plugin).

## Running the tests

The tests live in the `jvmTest` source set and cover `DesktopSubscriptionBiller`: a paid plan opens the configured checkout URL, `BASIC` opens nothing and reports unsubscribed, and cancellation opens the management URL.

```bash
./gradlew :client:billing:jvmTest
```

## Things to know

- `IosSubscriptionBiller` throws an error from `purchasePlan` when the plan's SKU is not among the products StoreKit returned, so a SKU missing from App Store Connect shows up as a failed purchase.
- `PlayStoreSubscriptionBiller` throws when no foreground `Activity` is available, because Play Billing cannot start without one.
