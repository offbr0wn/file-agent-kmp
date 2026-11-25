-- One row per app install that signs up. The token is never stored, only its hash.
CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  plan TEXT NOT NULL DEFAULT 'free',
  plan_until INTEGER NOT NULL DEFAULT 0,        -- unix seconds
  monthly_left INTEGER NOT NULL DEFAULT 0,
  pack_left INTEGER NOT NULL DEFAULT 0,
  cost_month REAL NOT NULL DEFAULT 0,           -- USD of AI fees this month (safety net)
  cost_month_key TEXT NOT NULL DEFAULT '',      -- e.g. 2026-11
  stripe_customer TEXT,
  stripe_subscription TEXT,
  ip_hash TEXT,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX accounts_customer ON accounts (stripe_customer) WHERE stripe_customer IS NOT NULL;
CREATE INDEX accounts_ip ON accounts (ip_hash, created_at);

-- One row per message. A message can take several AI steps.
CREATE TABLE requests (
  account_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  calls INTEGER NOT NULL DEFAULT 1,
  source TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, request_id)
);
CREATE INDEX requests_recent ON requests (account_id, created_at);

-- Stripe events already handled, so a repeat never adds credits twice.
CREATE TABLE payment_events (
  event_id TEXT PRIMARY KEY,
  account_id TEXT,
  kind TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
