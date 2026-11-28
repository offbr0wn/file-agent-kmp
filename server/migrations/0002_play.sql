-- Google Play purchases. One row per purchase token, so a purchase is only ever counted once.
CREATE TABLE IF NOT EXISTS play_purchases (
  token TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  product TEXT NOT NULL,
  kind TEXT NOT NULL,          -- 'subs' or 'inapp'
  expiry INTEGER NOT NULL DEFAULT 0, -- subs: end of the paid period (unix seconds)
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS play_purchases_account ON play_purchases(account_id);

-- The Play subscription behind an account's Plus, and when we last asked Google about it.
ALTER TABLE accounts ADD COLUMN play_token TEXT;
ALTER TABLE accounts ADD COLUMN play_checked_at INTEGER NOT NULL DEFAULT 0;
