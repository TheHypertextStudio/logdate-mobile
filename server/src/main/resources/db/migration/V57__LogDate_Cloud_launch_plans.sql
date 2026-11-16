-- V57: The two LogDate Cloud launch plans, and the record of who joined during the beta.
--
-- The catalog seeded in V16 (`free` at 1 GB, `standard` at 100 GB, `pro` with no limit) was a
-- placeholder. LogDate Cloud launches with two plans:
--
--   cloud       LogDate Cloud       50 GB included, nothing past it
--   cloud_plus  LogDate Cloud Plus  250 GB included, then extra storage in 50 GB steps, billed by use
--
-- Prices are not stored here. Stripe holds them, and the server reads them from Stripe by lookup
-- key (`logdate_<plan id>_monthly`, `logdate_<plan id>_yearly`), so what the app shows is always
-- what Stripe charges.
-- The size of one step of extra storage a plan sells past its included amount. NULL means the plan
-- stops at its included storage.
ALTER TABLE plans
ADD COLUMN IF NOT EXISTS extra_storage_step_bytes BIGINT;

-- Whether an account joined while LogDate Cloud was in beta. A member has the base plan at no
-- charge for as long as the account exists, whatever happens to any paid subscription it starts
-- later, so the mark lives on the account and not in `account_entitlements`, whose one row per
-- account a subscription rewrites. The server sets it when it creates an account: true until
-- LogDate Cloud launches, false afterwards. The column defaults to true for the server version still running
-- while this migration is applied, which creates accounts without naming the column; those
-- accounts are created during the beta too.
ALTER TABLE accounts
ADD COLUMN IF NOT EXISTS cloud_beta_member BOOLEAN NOT NULL DEFAULT TRUE;

-- When a paid subscription is set to stop, and how often it bills. Both come from the billing
-- provider and are shown in Settings; neither affects what the account may store.
ALTER TABLE account_entitlements
ADD COLUMN IF NOT EXISTS cancel_at TIMESTAMPTZ;

ALTER TABLE account_entitlements
ADD COLUMN IF NOT EXISTS billing_interval TEXT;

-- 'month' | 'year'
INSERT INTO
  plans (
    id,
    name,
    tier,
    monthly_bytes_limit,
    backup_count_limit,
    features,
    extra_storage_step_bytes,
    active
  )
VALUES
  (
    'cloud',
    'LogDate Cloud',
    'standard',
    53687091200,
    NULL,
    '{"email_verification":true}',
    NULL,
    TRUE
  ), -- 50 GB
  (
    'cloud_plus',
    'LogDate Cloud Plus',
    'pro',
    268435456000,
    NULL,
    '{"email_verification":true}',
    53687091200,
    TRUE
  ) -- 250 GB, then 50 GB steps
ON CONFLICT (id) DO NOTHING;

-- Move anything granted on the old plans to its nearest launch plan, then retire the old rows.
UPDATE account_entitlements
SET
  plan_id = 'cloud'
WHERE
  plan_id = 'standard';

UPDATE account_entitlements
SET
  plan_id = 'cloud_plus'
WHERE
  plan_id = 'pro';

UPDATE account_entitlements
SET
  plan_id = 'cloud',
  source = 'beta'
WHERE
  plan_id = 'free';

-- Give every other existing account an explicit row on the base plan. The mark above is what
-- keeps the plan free; this row is for the server version still running while this migration is
-- applied, which reads an account without a row as being on `free`, a plan that no longer exists.
INSERT INTO
  account_entitlements (account_id, plan_id, source, status)
SELECT
  id,
  'cloud',
  'beta',
  'active'
FROM
  accounts
ON CONFLICT (account_id) DO NOTHING;

DELETE FROM plans
WHERE
  id IN ('free', 'standard', 'pro');

-- The identifier of the billing provider's customer for an account, so a second checkout reuses the
-- first one's customer instead of creating a duplicate.
CREATE TABLE IF NOT EXISTS billing_customers (
  account_id UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
  stripe_customer_id TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The extra storage last reported to the billing provider for an account, in whole steps. The
-- reporter compares against it to send a new value only when it changed or has gone stale.
CREATE TABLE IF NOT EXISTS storage_meter_reports (
  account_id UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
  reported_units INTEGER NOT NULL,
  reported_at TIMESTAMPTZ NOT NULL
);

-- Exposed binds the webhook audit payload as text, as V26 arranged for the other JSON columns.
ALTER TABLE billing_events
ALTER COLUMN payload TYPE TEXT USING payload::text;
