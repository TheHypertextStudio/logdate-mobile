-- Only completed provider writes can age into orphan recovery. Uncertain WRITING tickets remain fenced.
ALTER TABLE object_upload_intents
ADD COLUMN settled_at TIMESTAMPTZ;

-- Historical completed writes receive a full attachment grace from migration time.
UPDATE object_upload_intents
SET
  settled_at = CURRENT_TIMESTAMP
WHERE
  phase = 'STORED';

CREATE INDEX object_upload_intents_cleanup ON object_upload_intents (id)
WHERE
  phase IN ('STORED', 'DELETE_PENDING');
