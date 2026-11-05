-- Exact signed retries remain valid after the source identity has been retired.
ALTER TABLE identity_adoption_receipts
ADD COLUMN verified_revision BIGINT,
ADD COLUMN manifest_digest CHAR(64),
ADD COLUMN coverage_digest CHAR(64),
ADD COLUMN commit_body_digest CHAR(64),
ADD COLUMN commit_challenge_id UUID,
ADD COLUMN commit_nonce_hash CHAR(64),
ADD COLUMN commit_signature_digest CHAR(64),
ADD CONSTRAINT identity_adoption_commit_proof CHECK (
  (
    verified_revision IS NULL
    AND manifest_digest IS NULL
    AND coverage_digest IS NULL
    AND commit_body_digest IS NULL
    AND commit_challenge_id IS NULL
    AND commit_nonce_hash IS NULL
    AND commit_signature_digest IS NULL
  )
  OR (
    verified_revision > 0
    AND manifest_digest ~ '^[0-9a-f]{64}$'
    AND coverage_digest ~ '^[0-9a-f]{64}$'
    AND commit_body_digest ~ '^[0-9a-f]{64}$'
    AND commit_challenge_id IS NOT NULL
    AND commit_nonce_hash ~ '^[0-9a-f]{64}$'
    AND commit_signature_digest ~ '^[0-9a-f]{64}$'
    AND num_nonnulls (
      verified_revision,
      manifest_digest,
      coverage_digest,
      commit_body_digest,
      commit_challenge_id,
      commit_nonce_hash,
      commit_signature_digest
    ) = 7
  )
);

CREATE TRIGGER identity_adoption_receipt_immutable
BEFORE UPDATE ON identity_adoption_receipts FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

-- Recovery metadata is retained without a second copy of the sealed record bytes.
CREATE TABLE identity_adoption_record_receipts (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  target_rkey VARCHAR(512) NOT NULL,
  target_sequence BIGINT NOT NULL CHECK (target_sequence > 0),
  body_digest CHAR(64) NOT NULL,
  body_bytes BYTEA NOT NULL CHECK (octet_length(body_bytes) BETWEEN 1 AND 65536),
  PRIMARY KEY (operation_id, target_rkey),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  CHECK (body_digest = encode(sha256(body_bytes), 'hex'))
);

CREATE TABLE identity_adoption_origin_receipts (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  target_rkey VARCHAR(512) NOT NULL,
  source_kind VARCHAR(64) NOT NULL,
  source_key_hash CHAR(64) NOT NULL,
  source_collection VARCHAR(317) NOT NULL,
  source_rkey VARCHAR(512) NOT NULL,
  body_digest CHAR(64) NOT NULL,
  body_bytes BYTEA NOT NULL CHECK (octet_length(body_bytes) BETWEEN 1 AND 65536),
  PRIMARY KEY (
    operation_id,
    source_kind,
    source_key_hash,
    source_collection,
    source_rkey
  ),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  FOREIGN KEY (operation_id, target_rkey) REFERENCES identity_adoption_record_receipts (operation_id, target_rkey) ON DELETE CASCADE,
  CHECK (body_digest = encode(sha256(body_bytes), 'hex'))
);

CREATE TRIGGER identity_adoption_record_receipt_immutable
BEFORE UPDATE ON identity_adoption_record_receipts FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE TRIGGER identity_adoption_origin_receipt_immutable
BEFORE UPDATE ON identity_adoption_origin_receipts FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE TRIGGER count_identity_adoption_record_receipt
AFTER INSERT OR DELETE ON identity_adoption_record_receipts FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_origin ();

CREATE TRIGGER count_identity_adoption_origin_receipt
AFTER INSERT OR DELETE ON identity_adoption_origin_receipts FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_origin ();
