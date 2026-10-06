-- V12__add_customer_auth_subject.sql
-- Binds a customer to an identity-provider account (PRD-003 FR-10, plan S2 / decision D1).
--
-- auth_subject holds the Keycloak JWT `sub` claim. It is resolved by `sub` first; on the
-- first request of an unlinked account the service falls back to the verified `email`
-- claim once and writes auth_subject, so later look-ups never depend on email again.
-- NULL means "not linked yet"; UNIQUE guarantees one account maps to at most one customer.

ALTER TABLE customers ADD COLUMN auth_subject VARCHAR(64);
ALTER TABLE customers ADD CONSTRAINT uq_customers_auth_subject UNIQUE (auth_subject);

-- Backfill the seeded customers with the fixed user IDs of the shopper accounts
-- provisioned in docker/keycloak/realm-export.json (alice.tran, ben.nguyen, chi.le).
UPDATE customers SET auth_subject = '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b01' WHERE email = 'alice.tran@example.com';
UPDATE customers SET auth_subject = '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b02' WHERE email = 'ben.nguyen@example.com';
UPDATE customers SET auth_subject = '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b03' WHERE email = 'chi.le@example.com';
