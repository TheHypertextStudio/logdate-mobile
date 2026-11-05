CREATE TABLE identity_adoption_verifications (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  source_revision BIGINT NOT NULL CHECK (source_revision > 0),
  verified_revision BIGINT NOT NULL CHECK (verified_revision = source_revision + 1),
  hold_generation BIGINT NOT NULL CHECK (
    hold_generation > 0
    AND hold_generation <= source_revision
  ),
  challenge_id UUID NOT NULL UNIQUE,
  manifest_digest CHAR(64) NOT NULL,
  coverage_digest CHAR(64) NOT NULL CHECK (coverage_digest ~ '^[0-9a-f]{64}$'),
  body_digest CHAR(64) NOT NULL CHECK (body_digest ~ '^[0-9a-f]{64}$'),
  source_sequence BIGINT NOT NULL CHECK (source_sequence >= 0),
  manifest_bytes BYTEA NOT NULL CHECK (octet_length(manifest_bytes) BETWEEN 1 AND 16384),
  verified_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (operation_id, verified_revision),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  CHECK (
    manifest_digest = encode(sha256(manifest_bytes), 'hex')
  )
);

CREATE TRIGGER identity_adoption_verification_immutable
BEFORE UPDATE ON identity_adoption_verifications FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE FUNCTION count_identity_adoption_verification () RETURNS trigger AS $$
DECLARE
  v_account UUID;
  v_delta BIGINT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_account := NEW.account_id;
    v_delta := octet_length(NEW.manifest_bytes)::bigint;
    INSERT INTO account_storage_usage(account_id) VALUES (v_account) ON CONFLICT DO NOTHING;
  ELSE
    v_account := OLD.account_id;
    v_delta := -octet_length(OLD.manifest_bytes)::bigint;
  END IF;
  UPDATE account_storage_usage SET space_bytes = space_bytes + v_delta
    WHERE account_id = v_account AND space_bytes + v_delta >= 0;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM accounts WHERE id = v_account) THEN
    RAISE EXCEPTION 'Private verification accounting is inconsistent' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER count_identity_adoption_verification
AFTER INSERT OR DELETE ON identity_adoption_verifications FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_verification ();
