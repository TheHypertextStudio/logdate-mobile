CREATE TABLE atproto_app_passwords (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  did VARCHAR(255) NOT NULL,
  name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
  verifier TEXT NOT NULL CHECK (verifier ~ '^[a-f0-9]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at TIMESTAMPTZ,
  UNIQUE (id, account_id),
  UNIQUE (verifier)
);

CREATE UNIQUE INDEX atproto_app_passwords_active_name ON atproto_app_passwords (account_id, did, name)
WHERE
  revoked_at IS NULL;

ALTER TABLE atproto_sessions
ADD COLUMN app_password_id UUID;

ALTER TABLE atproto_sessions
ADD CONSTRAINT atproto_sessions_app_password_owner FOREIGN KEY (app_password_id, account_id) REFERENCES atproto_app_passwords (id, account_id) ON DELETE CASCADE;

CREATE INDEX atproto_sessions_app_password_id ON atproto_sessions (app_password_id)
WHERE
  app_password_id IS NOT NULL;

ALTER TABLE webauthn_challenges
DROP CONSTRAINT IF EXISTS challenge_type_valid;

ALTER TABLE webauthn_challenges
ADD CONSTRAINT challenge_type_valid CHECK (
  challenge_type IN (
    'registration',
    'authentication',
    'oauth_authorize',
    'step_up',
    'step_up_grant',
    'plc_publish',
    'app_password'
  )
);
