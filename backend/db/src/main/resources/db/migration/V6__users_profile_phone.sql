-- Mobile phone number of the user (E.164, e.g. +32470123456), copied from the Keycloak login
-- (OIDC "phone_number" claim, user-profile attribute phone_number) by the app.
-- Used to send SMS notifications from background threads. Nullable: users without a number get no SMS.
-- Contract: planning/CONTRACTS.md §4 / §5.
ALTER TABLE users_profile ADD COLUMN IF NOT EXISTS phone TEXT;
