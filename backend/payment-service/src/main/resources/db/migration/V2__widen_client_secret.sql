-- PayU's hosted-checkout integration stores the full signed form (action URL, txnid, amount,
-- hash, etc.) as JSON in client_secret so the frontend can rebuild and auto-submit it -- this
-- routinely exceeds the 500-char limit that was sized for short opaque secrets (e.g. Stripe's).
ALTER TABLE payment_intents ALTER COLUMN client_secret TYPE TEXT;
