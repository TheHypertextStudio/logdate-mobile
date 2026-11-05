ALTER TABLE accounts
ADD COLUMN identity_epoch BIGINT NOT NULL DEFAULT 0 CHECK (identity_epoch >= 0);

-- A computed CID does not prove whether an operation was sent to the directory.
-- Existing history remains unknown until reconciled from independent evidence.
ALTER TABLE hosted_plc_operations
ADD COLUMN publication_attempted BOOLEAN;

CREATE TABLE identity_publication_attempts (
  did VARCHAR(2048) PRIMARY KEY,
  attempted_at TIMESTAMPTZ NOT NULL
);

INSERT INTO
  identity_publication_attempts (did, attempted_at)
SELECT DISTINCT
  public_identity_did,
  CURRENT_TIMESTAMP
FROM
  accounts
WHERE
  public_identity_did IS NOT NULL;

CREATE TABLE identity_adoption_operations (
  id VARCHAR(64) PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  source_did VARCHAR(2048) NOT NULL,
  target_did VARCHAR(2048) NOT NULL,
  device_id VARCHAR(255) NOT NULL,
  source_epoch BIGINT NOT NULL CHECK (source_epoch >= 0),
  revision BIGINT NOT NULL CHECK (revision > 0),
  phase VARCHAR(16) NOT NULL CHECK (
    phase IN (
      'PREPARING',
      'FENCED',
      'VERIFIED',
      'COMMITTED',
      'CANCELED'
    )
  ),
  lease_expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (source_did <> target_did)
);

CREATE UNIQUE INDEX identity_adoption_active_account ON identity_adoption_operations (account_id)
WHERE
  phase IN ('PREPARING', 'FENCED', 'VERIFIED');

CREATE TABLE identity_adoption_reservations (
  target_did VARCHAR(2048) PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL UNIQUE REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE TABLE identity_adoption_receipts (
  operation_id VARCHAR(64) PRIMARY KEY REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  source_did VARCHAR(2048) NOT NULL,
  target_did VARCHAR(2048) NOT NULL,
  identity_epoch BIGINT NOT NULL CHECK (identity_epoch > 0),
  committed_at TIMESTAMPTZ NOT NULL
);

-- Retirement must survive account deletion and backup restoration.
CREATE TABLE retired_identity_dids (
  source_did VARCHAR(2048) PRIMARY KEY,
  account_id UUID NOT NULL,
  operation_id VARCHAR(64) NOT NULL UNIQUE,
  identity_epoch BIGINT NOT NULL CHECK (identity_epoch > 0),
  retired_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION preserve_identity_safety_history () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Identity safety history is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER preserve_identity_retirement
BEFORE UPDATE OR DELETE ON retired_identity_dids FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE TRIGGER preserve_identity_publication_attempts
BEFORE UPDATE OR DELETE ON identity_publication_attempts FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE FUNCTION preserve_publication_attempt () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.publication_attempted IS TRUE AND NEW.publication_attempted IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'Publication attempt history cannot be cleared' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER preserve_publication_attempt
BEFORE UPDATE OF publication_attempted ON hosted_plc_operations FOR EACH ROW
EXECUTE FUNCTION preserve_publication_attempt ();

CREATE FUNCTION record_identity_publication_attempt () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO identity_publication_attempts(did, attempted_at)
  VALUES (NEW.did, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
  RETURN NEW;
END;
$$;

CREATE TRIGGER record_identity_publication_attempt
AFTER INSERT OR UPDATE OF publication_attempted ON hosted_plc_operations FOR EACH ROW WHEN (NEW.publication_attempted IS TRUE)
EXECUTE FUNCTION record_identity_publication_attempt ();

-- An uncertain object deletion must never make its old path reusable.
ALTER TABLE space_blobs
ADD COLUMN purge_operation_id VARCHAR(64);

ALTER TABLE space_blobs
ADD COLUMN purge_identity_epoch BIGINT;

ALTER TABLE space_blobs
ADD CONSTRAINT space_blob_purge_binding CHECK (
  (
    purge_operation_id IS NULL
    AND purge_identity_epoch IS NULL
  )
  OR (
    purge_operation_id IS NOT NULL
    AND purge_identity_epoch IS NOT NULL
    AND purge_identity_epoch >= 0
  )
);

ALTER TABLE space_migrations
ADD COLUMN identity_epoch BIGINT NOT NULL DEFAULT 0 CHECK (identity_epoch >= 0);

CREATE TABLE retired_account_ids (
  account_id_hash VARCHAR(64) PRIMARY KEY,
  deleted_at TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER preserve_deleted_account_ids
BEFORE UPDATE OR DELETE ON retired_account_ids FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

ALTER TABLE hosted_plc_operations
ADD COLUMN identity_epoch BIGINT NOT NULL DEFAULT 0 CHECK (identity_epoch >= 0);

-- Retain physical-path retirement after completion; uncertain deletes must not hit a new copy.
CREATE TABLE legacy_object_deletions (
  operation_id VARCHAR(64) PRIMARY KEY,
  account_id UUID NOT NULL,
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('MEDIA', 'BACKUP', 'UNREFERENCED')),
  resource_id TEXT NOT NULL,
  identity_epoch BIGINT NOT NULL CHECK (identity_epoch >= 0),
  storage_path TEXT NOT NULL UNIQUE,
  completed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX legacy_object_deletions_pending ON legacy_object_deletions (account_id)
WHERE
  NOT completed;

-- No account FK: provider work may finish after the original account was deleted.
CREATE TABLE object_upload_intents (
  id VARCHAR(64) PRIMARY KEY,
  account_id UUID NOT NULL,
  identity_epoch BIGINT NOT NULL CHECK (identity_epoch >= 0),
  repo_did VARCHAR(2048),
  upload_kind VARCHAR(16) NOT NULL CHECK (
    upload_kind IN ('HOSTED_REPO', 'MEDIA', 'BACKUP', 'SPACE')
  ),
  space_uri TEXT,
  legacy_migration_id VARCHAR(64),
  legacy_migration_version BIGINT,
  storage_path TEXT NOT NULL UNIQUE,
  phase VARCHAR(16) NOT NULL CHECK (
    phase IN (
      'WRITING',
      'STORED',
      'ATTACHED',
      'DELETE_PENDING',
      'RETIRED'
    )
  ),
  CHECK (
    (
      upload_kind = 'HOSTED_REPO'
      AND repo_did IS NOT NULL
    )
    OR (
      upload_kind <> 'HOSTED_REPO'
      AND repo_did IS NULL
    )
  ),
  CHECK (
    (
      upload_kind = 'SPACE'
      AND space_uri IS NOT NULL
    )
    OR (
      upload_kind <> 'SPACE'
      AND space_uri IS NULL
    )
  ),
  CHECK (
    (legacy_migration_id IS NULL) = (legacy_migration_version IS NULL)
  ),
  CHECK (
    legacy_migration_id IS NULL
    OR (
      upload_kind = 'SPACE'
      AND length(legacy_migration_id) > 0
      AND legacy_migration_version > 0
    )
  )
);

CREATE INDEX object_upload_intents_pending ON object_upload_intents (account_id)
WHERE
  phase IN ('WRITING', 'STORED', 'DELETE_PENDING');

CREATE FUNCTION preserve_object_upload_binding () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id, NEW.account_id, NEW.identity_epoch, NEW.repo_did, NEW.upload_kind,
         NEW.space_uri, NEW.legacy_migration_id, NEW.legacy_migration_version, NEW.storage_path)
     IS DISTINCT FROM
     ROW(OLD.id, OLD.account_id, OLD.identity_epoch, OLD.repo_did, OLD.upload_kind,
         OLD.space_uri, OLD.legacy_migration_id, OLD.legacy_migration_version, OLD.storage_path) THEN
    RAISE EXCEPTION 'Object upload binding is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER object_upload_binding_immutable
BEFORE UPDATE ON object_upload_intents FOR EACH ROW
EXECUTE FUNCTION preserve_object_upload_binding ();

-- Adoption proof is distinct from sign-in, recovery, OAuth authorization and disconnect.
ALTER TABLE webauthn_challenges
DROP CONSTRAINT challenge_type_valid;

ALTER TABLE webauthn_challenges
ADD CONSTRAINT challenge_type_valid CHECK (
  challenge_type IN (
    'registration',
    'authentication',
    'oauth_authorize',
    'step_up',
    'step_up_grant',
    'bluesky_signin',
    'atproto_disconnect',
    'identity_adoption'
  )
);

CREATE TABLE identity_adoption_decisions (
  id UUID PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  operation_revision BIGINT NOT NULL CHECK (operation_revision > 0),
  binding_hash CHAR(64) NOT NULL CHECK (binding_hash ~ '^[0-9a-f]{64}$'),
  challenge_hash CHAR(64) NOT NULL UNIQUE CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
  device_origin VARCHAR(2048),
  device_public_key VARCHAR(87),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  authorized_at TIMESTAMPTZ,
  consumed_at TIMESTAMPTZ,
  CHECK (
    (
      device_origin IS NULL
      AND device_public_key IS NULL
    )
    OR (
      device_origin IS NOT NULL
      AND device_public_key IS NOT NULL
      AND device_public_key ~ '^[A-Za-z0-9_-]{87}$'
    )
  ),
  CHECK (
    expires_at > created_at
    AND expires_at <= created_at + INTERVAL '5 minutes'
  ),
  CHECK (
    authorized_at IS NULL
    OR (
      authorized_at >= created_at
      AND authorized_at < expires_at
    )
  ),
  CHECK (
    consumed_at IS NULL
    OR (
      authorized_at IS NOT NULL
      AND consumed_at >= authorized_at
      AND consumed_at < expires_at
    )
  )
);

CREATE INDEX identity_adoption_decisions_account ON identity_adoption_decisions (account_id, operation_id);

CREATE INDEX identity_adoption_decisions_expiry ON identity_adoption_decisions (expires_at);

CREATE FUNCTION preserve_identity_adoption_decision () RETURNS trigger AS $$
BEGIN
  IF (NEW.id, NEW.operation_id, NEW.account_id, NEW.operation_revision, NEW.binding_hash,
      NEW.challenge_hash, NEW.created_at, NEW.expires_at, NEW.device_origin, NEW.device_public_key) IS DISTINCT FROM
     (OLD.id, OLD.operation_id, OLD.account_id, OLD.operation_revision, OLD.binding_hash,
      OLD.challenge_hash, OLD.created_at, OLD.expires_at, OLD.device_origin, OLD.device_public_key)
     OR (OLD.authorized_at IS NOT NULL AND NEW.authorized_at IS DISTINCT FROM OLD.authorized_at)
     OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at) THEN
    RAISE EXCEPTION 'Identity adoption decisions cannot be rebound or recycled' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER identity_adoption_decision_immutable
BEFORE UPDATE ON identity_adoption_decisions FOR EACH ROW
EXECUTE FUNCTION preserve_identity_adoption_decision ();

-- A replacement confirmation cannot repeat an action already consumed for the same revision.
CREATE UNIQUE INDEX identity_adoption_one_consumed_decision ON identity_adoption_decisions (operation_id, operation_revision)
WHERE
  consumed_at IS NOT NULL;

-- This proof of accepted migration intent outlives its short-lived WebAuthn challenge.
-- Account ownership comes from the referenced operation; no second mutable owner is stored.
CREATE TABLE identity_adoption_authorizations (
  operation_id VARCHAR(64) PRIMARY KEY REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  decision_id UUID NOT NULL UNIQUE,
  operation_revision BIGINT NOT NULL CHECK (operation_revision > 0),
  binding_hash CHAR(64) NOT NULL CHECK (binding_hash ~ '^[0-9a-f]{64}$'),
  challenge_hash CHAR(64) NOT NULL CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
  device_origin VARCHAR(2048) NOT NULL,
  device_public_key VARCHAR(87) NOT NULL CHECK (device_public_key ~ '^[A-Za-z0-9_-]{87}$'),
  authorized_at TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER identity_adoption_authorization_immutable
BEFORE UPDATE ON identity_adoption_authorizations FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

CREATE TABLE identity_adoption_oauth_proofs (
  operation_id VARCHAR(64) PRIMARY KEY REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  binding_hash CHAR(64) NOT NULL CHECK (binding_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  CHECK (
    expires_at > created_at
    AND expires_at <= created_at + INTERVAL '5 minutes'
  )
);

CREATE TRIGGER identity_adoption_oauth_proof_immutable
BEFORE UPDATE ON identity_adoption_oauth_proofs FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

-- A challenge is a short-lived request capability, never a replacement for accepted intent.
CREATE TABLE identity_adoption_action_challenges (
  id UUID PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL REFERENCES identity_adoption_operations (id) ON DELETE CASCADE,
  operation_revision BIGINT NOT NULL CHECK (operation_revision > 0),
  operation_phase VARCHAR(16) NOT NULL CHECK (
    operation_phase IN ('PREPARING', 'FENCED', 'VERIFIED')
  ),
  action VARCHAR(32) NOT NULL CHECK (
    action IN (
      'HOLD',
      'RENEW',
      'STAGE_RECORDS',
      'BEGIN_BLOB',
      'FINALIZE_BLOB',
      'READ_STAGED',
      'VERIFY',
      'COMMIT',
      'CANCEL'
    )
  ),
  body_digest CHAR(64) NOT NULL CHECK (body_digest ~ '^[0-9a-f]{64}$'),
  nonce_hash CHAR(64) NOT NULL CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  CHECK (
    expires_at > created_at
    AND expires_at <= created_at + INTERVAL '30 seconds'
  ),
  CHECK (
    consumed_at IS NULL
    OR (
      consumed_at >= created_at
      AND consumed_at < expires_at
    )
  )
);

CREATE INDEX identity_adoption_action_challenges_operation ON identity_adoption_action_challenges (operation_id, expires_at);

CREATE FUNCTION preserve_identity_adoption_action_challenge () RETURNS trigger AS $$
BEGIN
  IF ROW(NEW.id, NEW.operation_id, NEW.operation_revision, NEW.operation_phase, NEW.action,
         NEW.body_digest, NEW.nonce_hash, NEW.created_at, NEW.expires_at)
     IS DISTINCT FROM ROW(OLD.id, OLD.operation_id, OLD.operation_revision, OLD.operation_phase, OLD.action,
                          OLD.body_digest, OLD.nonce_hash, OLD.created_at, OLD.expires_at)
     OR OLD.consumed_at IS NOT NULL OR NEW.consumed_at IS NULL THEN
    RAISE EXCEPTION 'Identity action binding is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER identity_adoption_action_challenge_immutable
BEFORE UPDATE ON identity_adoption_action_challenges FOR EACH ROW
EXECUTE FUNCTION preserve_identity_adoption_action_challenge ();

-- Renewals retain a hold generation; a restarted hold cannot reuse an earlier verified copy.
ALTER TABLE identity_adoption_operations
ADD COLUMN hold_generation BIGINT NOT NULL DEFAULT 0 CHECK (hold_generation >= 0);

CREATE UNIQUE INDEX identity_adoption_operation_owner ON identity_adoption_operations (id, account_id);

CREATE TABLE identity_adoption_staged_records (
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  hold_generation BIGINT NOT NULL CHECK (hold_generation > 0),
  rkey VARCHAR(512) NOT NULL,
  source_collection VARCHAR(317) NOT NULL,
  source_rkey VARCHAR(512) NOT NULL,
  source_version BIGINT NOT NULL CHECK (source_version >= 0),
  source_digest CHAR(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  target_digest CHAR(64) NOT NULL CHECK (target_digest ~ '^[0-9a-f]{64}$'),
  target_cid VARCHAR(255),
  target_record BYTEA NOT NULL,
  blob_cids BYTEA NOT NULL,
  deleted BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (operation_id, hold_generation, rkey),
  UNIQUE (
    operation_id,
    hold_generation,
    source_collection,
    source_rkey
  ),
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  CHECK (
    (
      deleted
      AND target_cid IS NULL
    )
    OR (
      NOT deleted
      AND target_cid IS NOT NULL
    )
  ),
  CHECK (octet_length(target_record) BETWEEN 1 AND 4008192)
);

CREATE TRIGGER identity_adoption_staged_record_immutable
BEFORE UPDATE ON identity_adoption_staged_records FOR EACH ROW
EXECUTE FUNCTION preserve_identity_safety_history ();

-- Private copies remain charged when an operation is paused or canceled, including for old writers.
CREATE FUNCTION count_identity_adoption_staging () RETURNS trigger AS $$
DECLARE
  v_account UUID;
  v_delta BIGINT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_account := NEW.account_id;
    v_delta := octet_length(NEW.target_record);
    INSERT INTO account_storage_usage(account_id) VALUES (v_account) ON CONFLICT DO NOTHING;
  ELSE
    v_account := OLD.account_id;
    v_delta := -octet_length(OLD.target_record);
  END IF;
  UPDATE account_storage_usage SET space_bytes = space_bytes + v_delta
    WHERE account_id = v_account AND space_bytes + v_delta >= 0;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM accounts WHERE id = v_account) THEN
    RAISE EXCEPTION 'Private copy accounting is inconsistent' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER count_identity_adoption_staging
AFTER INSERT OR DELETE ON identity_adoption_staged_records FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_staging ();

-- Reused source ciphertext is already charged. Private pins retain it without a live target alias.
CREATE TABLE identity_adoption_staged_blobs (
  id VARCHAR(64) PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  hold_generation BIGINT NOT NULL CHECK (hold_generation > 0),
  source_space TEXT NOT NULL,
  cid VARCHAR(255) NOT NULL,
  storage_path TEXT NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 0 AND 104857600),
  mime_type VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  verified_at TIMESTAMPTZ,
  FOREIGN KEY (operation_id, account_id) REFERENCES identity_adoption_operations (id, account_id) ON DELETE CASCADE,
  UNIQUE (operation_id, hold_generation, cid),
  CHECK (
    verified_at IS NULL
    OR verified_at >= created_at
  )
);

CREATE INDEX identity_adoption_blob_retention ON identity_adoption_staged_blobs (storage_path);

CREATE FUNCTION preserve_identity_adoption_blob () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id, NEW.operation_id, NEW.account_id, NEW.hold_generation, NEW.source_space,
         NEW.cid, NEW.storage_path, NEW.size_bytes, NEW.mime_type, NEW.created_at)
     IS DISTINCT FROM
     ROW(OLD.id, OLD.operation_id, OLD.account_id, OLD.hold_generation, OLD.source_space,
         OLD.cid, OLD.storage_path, OLD.size_bytes, OLD.mime_type, OLD.created_at)
     OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM OLD.verified_at) THEN
    RAISE EXCEPTION 'Private media admission is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER identity_adoption_blob_immutable
BEFORE UPDATE ON identity_adoption_staged_blobs FOR EACH ROW
EXECUTE FUNCTION preserve_identity_adoption_blob ();
