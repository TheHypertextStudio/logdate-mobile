CREATE TABLE bluesky_sign_in_attempts (
  attempt_key VARCHAR(64) PRIMARY KEY,
  encrypted_payload TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bluesky_sign_in_attempts_expiry ON bluesky_sign_in_attempts (expires_at);
