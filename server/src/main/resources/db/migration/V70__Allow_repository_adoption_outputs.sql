DO $$
DECLARE
  v_constraint NAME;
  v_count INTEGER := 0;
BEGIN
  FOR v_constraint IN
    SELECT c.conname FROM pg_constraint c
    WHERE c.conrelid = 'identity_adoption_staged_origins'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
           FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ordinality)
           JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
        = ARRAY['operation_id', 'copy_generation', 'source_kind', 'source_key_hash', 'source_collection', 'source_rkey']
  LOOP
    EXECUTE format('ALTER TABLE identity_adoption_staged_origins DROP CONSTRAINT %I', v_constraint);
    v_count := v_count + 1;
  END LOOP;
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Private origin uniqueness is inconsistent' USING ERRCODE = '23514';
  END IF;
END;
$$ LANGUAGE plpgsql;

CREATE UNIQUE INDEX identity_adoption_single_component_origin ON identity_adoption_staged_origins (
  operation_id,
  copy_generation,
  source_kind,
  source_key_hash,
  source_collection,
  source_rkey
)
WHERE
  source_kind <> 'REPO_BLOCK';

DO $$
DECLARE
  v_constraint NAME;
  v_count INTEGER := 0;
BEGIN
  FOR v_constraint IN
    SELECT c.conname FROM pg_constraint c
    WHERE c.conrelid = 'identity_adoption_staged_records'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
           FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ordinality)
           JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
        = ARRAY['operation_id', 'hold_generation', 'source_collection', 'source_rkey']
  LOOP
    EXECUTE format('ALTER TABLE identity_adoption_staged_records DROP CONSTRAINT %I', v_constraint);
    v_count := v_count + 1;
  END LOOP;
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Private record uniqueness is inconsistent' USING ERRCODE = '23514';
  END IF;
END;
$$ LANGUAGE plpgsql;

CREATE UNIQUE INDEX identity_adoption_personal_source_record ON identity_adoption_staged_records (
  operation_id,
  hold_generation,
  source_collection,
  source_rkey
)
WHERE
  target_sequence IS NULL;

ALTER TABLE identity_adoption_origin_receipts
DROP CONSTRAINT identity_adoption_origin_receipts_pkey;

ALTER TABLE identity_adoption_origin_receipts
ADD PRIMARY KEY (
  operation_id,
  target_rkey,
  source_kind,
  source_key_hash,
  source_collection,
  source_rkey
);

CREATE UNIQUE INDEX identity_adoption_single_component_receipt ON identity_adoption_origin_receipts (
  operation_id,
  source_kind,
  source_key_hash,
  source_collection,
  source_rkey
)
WHERE
  source_kind <> 'REPO_BLOCK';
