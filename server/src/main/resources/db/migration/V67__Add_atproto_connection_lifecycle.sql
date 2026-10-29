-- Connection evidence remains after encrypted provider tokens have been removed.
-- Existing token rows acquire no fabricated lifecycle history during migration.
CREATE TABLE atproto_connections (
  did VARCHAR(2048) PRIMARY KEY,
  state VARCHAR(32) NOT NULL CHECK (
    state IN (
      'connected',
      'expired',
      'revoked',
      'reconnect_required',
      'disconnected'
    )
  ),
  revision BIGINT NOT NULL CHECK (revision > 0),
  observed_at TIMESTAMPTZ NOT NULL,
  last_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX atproto_connections_activity_idx ON atproto_connections (last_activity_at);

-- The operation identifier and WebAuthn challenge are retained only as digests.
-- Assertions, provider tokens and provider errors do not belong in these records.
CREATE TABLE atproto_disconnect_decisions (
  decision_key CHAR(64) PRIMARY KEY CHECK (decision_key ~ '^[0-9a-f]{64}$'),
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  did VARCHAR(2048) NOT NULL,
  expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
  challenge_hash CHAR(64) NOT NULL UNIQUE CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  authorized_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  disconnected_revision BIGINT,
  remote_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
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
    (
      completed_at IS NULL
      AND disconnected_revision IS NULL
      AND NOT remote_confirmed
    )
    OR (
      completed_at IS NOT NULL
      AND disconnected_revision IS NOT NULL
      AND disconnected_revision > expected_revision
      AND authorized_at IS NOT NULL
      AND completed_at >= authorized_at
    )
  )
);

CREATE INDEX atproto_disconnect_pending_expiry_idx ON atproto_disconnect_decisions (expires_at)
WHERE
  completed_at IS NULL;

CREATE INDEX atproto_disconnect_receipt_retention_idx ON atproto_disconnect_decisions (completed_at)
WHERE
  completed_at IS NOT NULL;
