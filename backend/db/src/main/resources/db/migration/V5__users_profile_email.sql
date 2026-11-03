-- Email address of the user, copied from the Keycloak login (OIDC "email" claim) by the app.
-- Used to send target-price-hit alert emails from background threads (no request / login context there).
-- Nullable: users without an email address simply get no alert emails. Contract: planning/CONTRACTS.md §4 / §5.
ALTER TABLE users_profile ADD COLUMN IF NOT EXISTS email TEXT;
