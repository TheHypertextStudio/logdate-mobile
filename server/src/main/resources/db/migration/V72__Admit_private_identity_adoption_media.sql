-- Physical upload tickets survive operation/account deletion and retain uncertain provider writes.
CREATE TABLE identity_adoption_media_uploads (
  id VARCHAR(64) PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL,
  account_id UUID NOT NULL,
  identity_epoch BIGINT NOT NULL CHECK (identity_epoch >= 0),
  source_kind VARCHAR(32) NOT NULL CHECK (
    source_kind IN ('SYNC_MEDIA', 'MEDIA', 'ATPROTO_BLOB')
  ),
  source_key TEXT NOT NULL,
  source_digest CHAR(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  cid VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 0 AND 104857600),
  blob_id VARCHAR(64) NOT NULL UNIQUE,
  owner_token VARCHAR(43) NOT NULL UNIQUE CHECK (owner_token ~ '^[A-Za-z0-9_-]{43}$'),
  storage_path TEXT NOT NULL UNIQUE,
  phase VARCHAR(16) NOT NULL CHECK (
    phase IN (
      'ADMITTED',
      'WRITING',
      'STORED',
      'VERIFIED',
      'PROMOTED',
      'DELETE_PENDING',
      'RETIRED'
    )
  ),
  created_at TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ NOT NULL,
  settled_at TIMESTAMPTZ
);

CREATE INDEX identity_adoption_media_owner ON identity_adoption_media_uploads (operation_id, account_id);

CREATE FUNCTION preserve_identity_adoption_media_upload () RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id,NEW.operation_id,NEW.account_id,NEW.identity_epoch,NEW.source_kind,NEW.source_key,NEW.source_digest,
         NEW.cid,NEW.size_bytes,NEW.blob_id,NEW.owner_token,NEW.storage_path,NEW.created_at)
     IS DISTINCT FROM
     ROW(OLD.id,OLD.operation_id,OLD.account_id,OLD.identity_epoch,OLD.source_kind,OLD.source_key,OLD.source_digest,
         OLD.cid,OLD.size_bytes,OLD.blob_id,OLD.owner_token,OLD.storage_path,OLD.created_at) THEN
    RAISE EXCEPTION 'Private upload ownership is immutable' USING ERRCODE = '23514';
  END IF;
  IF NOT (NEW.phase = OLD.phase OR
    (OLD.phase='ADMITTED' AND NEW.phase IN ('WRITING','DELETE_PENDING')) OR
    (OLD.phase='WRITING' AND NEW.phase IN ('STORED','VERIFIED','DELETE_PENDING')) OR
    (OLD.phase='STORED' AND NEW.phase IN ('VERIFIED','DELETE_PENDING')) OR
    (OLD.phase='VERIFIED' AND NEW.phase IN ('PROMOTED','DELETE_PENDING')) OR
    (OLD.phase='PROMOTED' AND NEW.phase='DELETE_PENDING') OR
    (OLD.phase='DELETE_PENDING' AND NEW.phase='RETIRED')) THEN
    RAISE EXCEPTION 'Invalid private upload transition' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER preserve_identity_adoption_media_upload
BEFORE UPDATE ON identity_adoption_media_uploads FOR EACH ROW
EXECUTE FUNCTION preserve_identity_adoption_media_upload ();

CREATE FUNCTION count_identity_adoption_media_upload () RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_before BIGINT := 0;
  v_after BIGINT := 0;
BEGIN
  IF TG_OP <> 'INSERT' AND OLD.phase NOT IN ('PROMOTED','RETIRED') THEN v_before := OLD.size_bytes; END IF;
  IF TG_OP <> 'DELETE' AND NEW.phase NOT IN ('PROMOTED','RETIRED') THEN v_after := NEW.size_bytes; END IF;
  IF TG_OP = 'INSERT' THEN
    INSERT INTO account_storage_usage(account_id) VALUES (NEW.account_id) ON CONFLICT DO NOTHING;
  END IF;
  UPDATE account_storage_usage SET space_bytes=space_bytes+v_after-v_before
    WHERE account_id=COALESCE(NEW.account_id,OLD.account_id) AND space_bytes+v_after-v_before >= 0;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM accounts WHERE id=COALESCE(NEW.account_id,OLD.account_id)) THEN
    RAISE EXCEPTION 'Private upload accounting is inconsistent' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$$;

CREATE TRIGGER count_identity_adoption_media_upload
AFTER INSERT OR UPDATE OR DELETE ON identity_adoption_media_uploads FOR EACH ROW
EXECUTE FUNCTION count_identity_adoption_media_upload ();
