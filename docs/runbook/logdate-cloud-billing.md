# LogDate Cloud billing runbook

This runbook covers the tasks an operator performs to sell LogDate Cloud plans: setting a Stripe
account up, connecting the server to it, launching, and looking into a charge or a plan that
looks wrong. The [environment variable reference](../../server/docs/environment-variables.md#billing)
lists every setting these steps mention.

There are two plans. LogDate Cloud includes 50 GB and stops there. LogDate Cloud Plus includes
250 GB and sells storage past that in 50 GB steps, charged each month only for the steps in use.

During the beta the base plan is free, and everyone who joins keeps it free afterwards. Plus is a
paid plan from the start: people can buy it as soon as the server is connected to Stripe.

Do every step in Stripe's test mode against staging first. Test mode charges nobody, and the steps
are the same in live mode.

## Set up a Stripe account

The server finds LogDate Cloud's prices in Stripe by lookup key and reports extra storage to a
billing meter. One command creates all of it, and running it again changes nothing that already
exists.

1. Create an API key for the server in the Stripe Dashboard, under Developers, API keys. Use test
   mode for staging.
2. From a checkout of this repository, check what the account has:

   ```bash
   STRIPE_SECRET_KEY=<the key> ./gradlew :server:run --args="--stripe-catalog status --report-file stripe-catalog.txt"
   ```

   The log ends with `SERVER_BILLING_CATALOG_READY` or `SERVER_BILLING_CATALOG_NOT_READY`, and
   `stripe-catalog.txt` has one line for each price and for the meter: `ok`, `missing` or `differs`.

3. Create what is missing by running the same command with `apply` in place of `status`. It creates
   the monthly and yearly price of each plan, the meter `logdate_cloud_extra_storage`, and the
   usage-billed price for extra storage on Plus, at the launch amounts.
4. A price reported as `differs` exists with another amount, currency or interval. The command
   never changes it, because people may be paying it. To change what a plan costs, create a new
   price in Stripe and move the lookup key to it; existing subscribers stay on the price they have.

## Connect the server to Stripe

1. In the Stripe Dashboard, add a webhook endpoint at
   `https://<the server's public origin>/api/v1/billing/stripe/webhook` that sends these events:
   `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`,
   `customer.subscription.deleted`, `customer.subscription.paused` and `customer.subscription.resumed`.
   Copy the endpoint's signing secret when Stripe shows it.
2. Run the catalog command again with `--webhook-url <that address>` added. The webhook's line
   reads `ok` when the endpoint exists and sends every event the server acts on.
3. In the Stripe Dashboard, open Settings, Billing, Customer portal, and save the portal with
   payment methods and invoice history switched on and with cancelling and switching plans switched
   off. People cancel and switch plans in LogDate, which keeps what the app shows and what Stripe
   bills in step; the portal is for their card and their receipts. Stripe refuses to open a portal
   that has never been saved.
4. Store the API key and the webhook signing secret in Secret Manager, map them as
   `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` in the environment's `secret_env`, and set
   `BILLING_PROVIDER` to `stripe` in its `cloud_run_env`.
5. Deploy. `GET /api/v1/plans` now lists each plan with its prices. Plus has `onSale: true` and
   can be bought from this point; the base plan stays `onSale: false` until launch.
6. Check it end to end in staging before production: buy Plus with a
   [Stripe test card](https://docs.stripe.com/testing), store more than Plus includes, and confirm
   the next invoice in Stripe carries the extra storage.
7. Turn on the LogDate Cloud plan setting in the app, under Settings, Labs, until the plan screen
   ships to everyone.

## Launch

Launch ends the beta. From that moment the base plan is sold too, and an account created has no
plan until it buys one.

The app sells plans through Stripe's checkout in the browser, not through an app store's own
billing. Before the plan screen is shown to everyone, check each store's rules on buying a
subscription outside its billing, for every region LogDate is offered in.

1. Set `LOGDATE_CLOUD_LAUNCHED` to `"true"` in the environment's `cloud_run_env` and deploy. The
   deploy contract refuses it unless `BILLING_PROVIDER` is `stripe`, and the server refuses to
   start with it unless both Stripe secrets are set.
2. Confirm `GET /api/v1/plans` shows `onSale: true` for both plans.

Every account that existed before this step is a beta member and keeps the base plan at no charge.
Turning the setting off again stops selling the base plan and resumes enrolling new accounts as
beta members.

## How extra storage is charged

Plus includes 250 GB. Past that the server keeps accepting uploads and reports, every fifteen
minutes, how many whole 50 GB steps each Plus subscriber is using past it. Stripe bills the last
value reported in each monthly period at the extra-storage price, so the charge follows what is in
use when the bill is made.

- Someone who frees the space before their bill pays nothing extra for that month, even if they
  used it for part of it. Someone who goes over shortly before their bill pays for a whole step.
  Charging by the day would be more exact and much harder to read on an invoice.
- A subscriber whose payment is failing keeps what they store but cannot grow past 250 GB until
  the payment is fixed. Their use is still reported.
- The report goes out only while the server runs. A period in which the server reported nothing
  for an account bills no extra storage for it.
- An account granted Plus without a Stripe subscription cannot be billed, so it stops at 250 GB.

## Look into a plan or a charge

- **The app shows the wrong plan.** When someone returns to the plan screen from a checkout, the
  app asks the server to read the account's subscriptions back from Stripe, and the screen's Check
  again button does the same. The server also applies every webhook by reading the subscription
  from Stripe, so a late or repeated event cannot leave a stale plan.
- **Webhooks are failing.** `SERVER_BILLING_WEBHOOK_REJECTED` in the logs means a signature did
  not verify: the signing secret in Secret Manager is not the endpoint's.
  `SERVER_BILLING_WEBHOOK_NOT_APPLIED` means Stripe could not be read; Stripe sends the event again.
- **Extra storage is missing from an invoice.** `SERVER_BILLING_METER_REPORT_FAILED` means a
  report was refused for one account and is retried on the next pass.
  `SERVER_BILLING_METERED_PRICE_MISSING` means Stripe has no price with the lookup key
  `logdate_cloud_plus_extra_storage`; run the catalog command.
- **Someone was refused an upload.** The refusal says why in `details.reason`: `STORAGE_BYTES` for a
  full plan that sells no more, `EXTRA_STORAGE_UNPAID` for Plus with a failing payment, and
  `NO_PLAN` for an account that has no plan.
- **An account is deleted.** Its subscription is ended in Stripe first. If Stripe cannot be reached
  the deletion fails and can be tried again, so no deleted account keeps being charged.
