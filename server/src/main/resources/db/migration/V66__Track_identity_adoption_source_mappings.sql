CREATE TABLE identity_adoption_source_mappings (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  copy_generation BIGINT NOT NULL CHECK (copy_generation > 0),
  source_kind VARCHAR(64) NOT NULL,
  source_key_hash CHAR(64) NOT NULL,
  source_key BYTEA NOT NULL CHECK (octet_length(source_key) BETWEEN 1 AND 32768),
  source_digest CHAR(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  disposition VARCHAR(32) NOT NULL CHECK (
    disposition IN (
      'CONVERTED',
      'STRUCTURAL',
      'RETAINED_HISTORY',
      'MEDIA',
      'REFERENCES'
    )
  ),
  body_digest CHAR(64) NOT NULL,
  body_bytes BYTEA NOT NULL CHECK (octet_length(body_bytes) BETWEEN 1 AND 131072),
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (
    operation_id,
    copy_generation,
    source_kind,
    source_key_hash
  ),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  CHECK (
    source_key_hash = encode(sha256(source_key), 'hex')
  ),
  CHECK (body_digest = encode(sha256(body_bytes), 'hex'))
);

CREATE TRIGGER identity_adoption_source_mapping_immutable
BEFORE UPDATE ON identity_adoption_source_mappings FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE FUNCTION count_identity_adoption_mapping () RETURNS trigger AS $$
DECLARE
  v_account UUID;
  v_delta BIGINT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_account := NEW.account_id;
    v_delta := octet_length(NEW.source_key)::bigint + octet_length(NEW.body_bytes);
    INSERT INTO account_storage_usage(account_id) VALUES (v_account) ON CONFLICT DO NOTHING;
  ELSE
    v_account := OLD.account_id;
    v_delta := -(octet_length(OLD.source_key)::bigint + octet_length(OLD.body_bytes));
  END IF;
  UPDATE account_storage_usage SET space_bytes = space_bytes + v_delta
    WHERE account_id = v_account AND space_bytes + v_delta >= 0;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM accounts WHERE id = v_account) THEN
    RAISE EXCEPTION 'Private mapping accounting is inconsistent' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER count_identity_adoption_mapping
AFTER INSERT OR DELETE ON identity_adoption_source_mappings FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_mapping ();
