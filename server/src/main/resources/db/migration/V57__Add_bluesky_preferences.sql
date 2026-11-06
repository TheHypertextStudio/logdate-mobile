CREATE TABLE bluesky_preferences (
  account_id UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
  preferences TEXT NOT NULL
);
