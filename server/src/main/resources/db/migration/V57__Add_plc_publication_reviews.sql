-- A review ID binds the passkey assertion to one immutable operation snapshot.
CREATE TABLE plc_publication_reviews (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  did TEXT NOT NULL,
  handle TEXT NOT NULL,
  operations JSONB NOT NULL CHECK (
    jsonb_typeof(operations) = 'array'
    AND operations <> '[]'::jsonb
  ),
  prepared_cid TEXT CHECK (
    prepared_cid IS NULL
    OR jsonb_array_length(operations) >= 2
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  authorized_at TIMESTAMPTZ,
  first_attempt_at TIMESTAMPTZ,
  confirmed_at TIMESTAMPTZ,
  next_check_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  state TEXT NOT NULL DEFAULT 'review',
  CHECK (
    (
      state = 'review'
      AND authorized_at IS NULL
      AND first_attempt_at IS NULL
      AND confirmed_at IS NULL
    )
    OR (
      state = 'authorized'
      AND authorized_at IS NOT NULL
      AND first_attempt_at IS NULL
      AND confirmed_at IS NULL
    )
    OR (
      state = 'uncertain'
      AND authorized_at IS NOT NULL
      AND first_attempt_at IS NOT NULL
      AND confirmed_at IS NULL
    )
    OR (
      state = 'conflict'
      AND authorized_at IS NOT NULL
      AND confirmed_at IS NULL
    )
    OR (
      state = 'confirmed'
      AND authorized_at IS NOT NULL
      AND confirmed_at IS NOT NULL
    )
    OR (
      state = 'superseded'
      AND first_attempt_at IS NULL
      AND confirmed_at IS NULL
    )
  ),
  CHECK (
    authorized_at IS NULL
    OR (
      authorized_at >= created_at
      AND authorized_at < expires_at
    )
  ),
  CHECK (
    first_attempt_at IS NULL
    OR first_attempt_at >= authorized_at
  ),
  CHECK (
    confirmed_at IS NULL
    OR confirmed_at >= COALESCE(first_attempt_at, authorized_at)
  )
);

CREATE UNIQUE INDEX plc_publication_reviews_active_account ON plc_publication_reviews (account_id)
WHERE
  state IN ('authorized', 'uncertain', 'conflict');

CREATE INDEX plc_publication_reviews_due ON plc_publication_reviews (next_check_at, id)
WHERE
  state IN ('authorized', 'uncertain');

CREATE INDEX plc_publication_reviews_account ON plc_publication_reviews (account_id, created_at DESC);

CREATE FUNCTION protect_plc_publication_review_snapshot () RETURNS TRIGGER LANGUAGE plpgsql
SET
  search_path = pg_catalog AS $$
BEGIN
    IF (NEW.id, NEW.account_id, NEW.did, NEW.handle, NEW.operations, NEW.prepared_cid, NEW.created_at, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.id, OLD.account_id, OLD.did, OLD.handle, OLD.operations, OLD.prepared_cid, OLD.created_at, OLD.expires_at)
    THEN
        RAISE EXCEPTION 'The reviewed publication snapshot is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plc_publication_review_snapshot_immutable
BEFORE UPDATE ON plc_publication_reviews FOR EACH ROW
EXECUTE FUNCTION protect_plc_publication_review_snapshot ();

ALTER TABLE webauthn_challenges
DROP CONSTRAINT IF EXISTS challenge_type_valid;

ALTER TABLE webauthn_challenges
ADD CONSTRAINT challenge_type_valid CHECK (
  challenge_type IN (
    'registration',
    'authentication',
    'oauth_authorize',
    'step_up',
    'step_up_grant',
    'plc_publish'
  )
);

-- Once transmission can have started, preserve the identity until directory reconciliation.
-- This check uses the account row lock also taken when the worker marks an attempt.
CREATE FUNCTION lock_plc_publication_identity (owner UUID) RETURNS VOID LANGUAGE plpgsql
SET
  search_path = pg_catalog,
  public AS $$
BEGIN
    PERFORM 1 FROM accounts WHERE id = owner FOR UPDATE;
    IF EXISTS (
        SELECT 1 FROM plc_publication_reviews
        WHERE account_id = owner AND first_attempt_at IS NOT NULL AND state IN ('uncertain', 'conflict')
    ) THEN
        RAISE EXCEPTION 'Account identity is awaiting publication reconciliation' USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION protect_plc_publication_account () RETURNS TRIGGER LANGUAGE plpgsql
SET
  search_path = pg_catalog,
  public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lock_plc_publication_identity(OLD.id);
        RETURN OLD;
    END IF;
    IF (NEW.did, NEW.handle, NEW.signing_key_public, NEW.plc_recovery_did_key)
       IS DISTINCT FROM (OLD.did, OLD.handle, OLD.signing_key_public, OLD.plc_recovery_did_key)
    THEN
        PERFORM lock_plc_publication_identity(OLD.id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plc_publication_account_unchanged
BEFORE UPDATE OF did,
handle,
signing_key_public,
plc_recovery_did_key OR DELETE ON accounts FOR EACH ROW
EXECUTE FUNCTION protect_plc_publication_account ();

CREATE FUNCTION protect_plc_publication_hosted_history () RETURNS TRIGGER LANGUAGE plpgsql
SET
  search_path = pg_catalog,
  public AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM lock_plc_publication_identity(NEW.account_id);
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.account_id IS DISTINCT FROM OLD.account_id THEN
        -- Lock both owners in a stable order.
        PERFORM lock_plc_publication_identity(LEAST(OLD.account_id, NEW.account_id));
        PERFORM lock_plc_publication_identity(GREATEST(OLD.account_id, NEW.account_id));
    ELSE
        PERFORM lock_plc_publication_identity(OLD.account_id);
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plc_publication_hosted_history_unchanged
BEFORE INSERT OR UPDATE OR DELETE ON hosted_plc_operations FOR EACH ROW
EXECUTE FUNCTION protect_plc_publication_hosted_history ();
