-- Existing identities remain hosted; external identities require verified enrollment or adoption.
ALTER TABLE accounts
ADD COLUMN identity_kind VARCHAR(32) NOT NULL DEFAULT 'hosted';

-- Hostname-only did:web identifiers can exceed the old 255-character limit.
ALTER TABLE accounts
ALTER COLUMN did TYPE VARCHAR(2048);
