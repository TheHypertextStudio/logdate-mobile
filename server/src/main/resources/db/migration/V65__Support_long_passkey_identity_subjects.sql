-- A WebAuthn credential ID can contain 1023 bytes (1364 base64url characters).
-- Keep the passkey identity usable for every supported credential size.
ALTER TABLE account_identities
ALTER COLUMN provider_subject TYPE VARCHAR(2048);
