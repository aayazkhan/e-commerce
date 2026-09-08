-- Mirrors payment-service's V2 migration: PayU's hosted-checkout form (action URL, txnid,
-- amount, hash, etc., stored as JSON) routinely exceeds the 500-char limit sized for short
-- opaque secrets (e.g. Stripe's), and checkout-service caches the same clientSecret here.
ALTER TABLE checkout_sagas ALTER COLUMN payment_client_secret TYPE TEXT;
