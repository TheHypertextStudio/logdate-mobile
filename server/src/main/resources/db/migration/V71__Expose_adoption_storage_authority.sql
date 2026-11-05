CREATE VIEW identity_adoption_storage_authority AS
SELECT
  a.id AS account_id,
  a.did AS repo_did,
  a.identity_epoch,
  a.storage_mode,
  m.migration_id,
  m.phase AS migration_phase,
  m.version AS migration_version,
  m.identity_epoch AS migration_identity_epoch,
  m.switched_at AS migration_switched_at,
  m.switched_at_seq AS migration_switched_at_seq,
  m.reverted_at AS migration_reverted_at,
  m.cleaned_at AS migration_cleaned_at
FROM
  accounts a
  LEFT JOIN space_migrations m ON m.account_id = a.id;
