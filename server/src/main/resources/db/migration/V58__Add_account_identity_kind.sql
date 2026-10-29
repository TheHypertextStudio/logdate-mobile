-- Existing identities remain hosted; external identities require verified enrollment or adoption.
ALTER TABLE accounts
ADD COLUMN identity_kind VARCHAR(32) NOT NULL DEFAULT 'hosted';

-- Hostname-only did:web identifiers can exceed the old 255-character limit.
-- PostgreSQL cannot retype a column named in a trigger, so the PLC publication guard from V58
-- is dropped and recreated unchanged around the widening.
DROP TRIGGER plc_publication_account_unchanged ON accounts;

ALTER TABLE accounts
ALTER COLUMN did TYPE VARCHAR(2048);

CREATE TRIGGER plc_publication_account_unchanged
BEFORE UPDATE OF did,
handle,
signing_key_public,
plc_recovery_did_key OR DELETE ON accounts FOR EACH ROW
EXECUTE FUNCTION protect_plc_publication_account ();
