-- Browser cancellation must outlive expiring provider state and delayed exchanges.
CREATE TABLE identity_adoption_browser_proposals (
  proposal_id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  source_did VARCHAR(2048) NOT NULL,
  source_epoch BIGINT NOT NULL CHECK (source_epoch >= 0),
  device_id VARCHAR(128) NOT NULL,
  attempt_key CHAR(64) NOT NULL UNIQUE CHECK (attempt_key ~ '^[0-9a-f]{64}$'),
  challenge_hash CHAR(64) NOT NULL CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  canceled_at TIMESTAMPTZ CHECK (canceled_at >= created_at)
);

CREATE INDEX identity_adoption_browser_account ON identity_adoption_browser_proposals (account_id);

CREATE FUNCTION preserve_identity_adoption_browser_proposal () RETURNS trigger AS $$
BEGIN
  IF (to_jsonb(NEW) - 'canceled_at') IS DISTINCT FROM (to_jsonb(OLD) - 'canceled_at')
     OR (OLD.canceled_at IS NOT NULL AND NEW.canceled_at IS DISTINCT FROM OLD.canceled_at) THEN
    RAISE EXCEPTION 'Identity adoption browser proposal is immutable';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER identity_adoption_browser_proposal_immutable
BEFORE UPDATE ON identity_adoption_browser_proposals FOR EACH ROW
EXECUTE FUNCTION preserve_identity_adoption_browser_proposal ();
