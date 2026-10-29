CREATE TABLE bluesky_oauth_states (
  state_key CHAR(64) PRIMARY KEY,
  encrypted_payload TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX bluesky_oauth_states_expiry_idx ON bluesky_oauth_states (expires_at);

CREATE TABLE bluesky_oauth_sessions (
  did VARCHAR(2048) PRIMARY KEY,
  encrypted_payload TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
