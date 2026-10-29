CREATE TABLE bluesky_account_handoffs (
  handoff_key VARCHAR(64) PRIMARY KEY,
  encrypted_payload TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bluesky_account_handoffs_expiry ON bluesky_account_handoffs (expires_at);
