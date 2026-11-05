ALTER TABLE identity_adoption_staged_records
ADD COLUMN target_sequence BIGINT CHECK (target_sequence > 0);

CREATE UNIQUE INDEX identity_adoption_target_sequence ON identity_adoption_staged_records (operation_id, hold_generation, target_sequence)
WHERE
  target_sequence IS NOT NULL;

CREATE TABLE identity_adoption_staged_origins (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  copy_generation BIGINT NOT NULL CHECK (copy_generation > 0),
  target_rkey VARCHAR(512) NOT NULL,
  source_kind VARCHAR(64) NOT NULL,
  source_key_hash CHAR(64) NOT NULL,
  source_collection VARCHAR(317) NOT NULL,
  source_rkey VARCHAR(512) NOT NULL,
  body_digest CHAR(64) NOT NULL,
  body_bytes BYTEA NOT NULL CHECK (octet_length(body_bytes) BETWEEN 1 AND 65536),
  PRIMARY KEY (
    operation_id,
    copy_generation,
    target_rkey,
    source_kind,
    source_key_hash,
    source_collection,
    source_rkey
  ),
  UNIQUE (
    operation_id,
    copy_generation,
    source_kind,
    source_key_hash,
    source_collection,
    source_rkey
  ),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  FOREIGN KEY (operation_id, copy_generation, target_rkey) REFERENCES identity_adoption_staged_records (operation_id, hold_generation, rkey) ON DELETE CASCADE,
  CHECK (body_digest = encode(sha256(body_bytes), 'hex'))
);

CREATE TRIGGER identity_adoption_copy_origin_immutable
BEFORE UPDATE ON identity_adoption_staged_origins FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE FUNCTION count_identity_adoption_origin () RETURNS trigger AS $$
DECLARE
  v_account UUID;
  v_delta BIGINT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_account := NEW.account_id;
    v_delta := octet_length(NEW.body_bytes)::bigint;
    INSERT INTO account_storage_usage(account_id) VALUES (v_account) ON CONFLICT DO NOTHING;
  ELSE
    v_account := OLD.account_id;
    v_delta := -octet_length(OLD.body_bytes)::bigint;
  END IF;
  UPDATE account_storage_usage SET space_bytes = space_bytes + v_delta
    WHERE account_id = v_account AND space_bytes + v_delta >= 0;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM accounts WHERE id = v_account) THEN
    RAISE EXCEPTION 'Private origin accounting is inconsistent' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER count_identity_adoption_origin
AFTER INSERT OR DELETE ON identity_adoption_staged_origins FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_origin ();
