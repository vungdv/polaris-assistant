-- V17__seed_load_test_shoppers.sql
-- Customers for the load-test accounts shopper.0 .. shopper.9 provisioned in docker/keycloak/polaris-realm.json
-- (fixed Keycloak user IDs, same scheme as the V12 backfill of alice.tran / ben.nguyen / chi.le).
-- Insert-only: a customer that already exists for the email is left alone (it links by verified email on first request, V12).

INSERT INTO customers (full_name, email, auth_subject)
SELECT v.full_name, v.email, v.auth_subject
FROM (VALUES
('Shopper 0', 'shopper.0@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b00'),
('Shopper 1', 'shopper.1@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b01'),
('Shopper 2', 'shopper.2@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b02'),
('Shopper 3', 'shopper.3@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b03'),
('Shopper 4', 'shopper.4@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b04'),
('Shopper 5', 'shopper.5@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b05'),
('Shopper 6', 'shopper.6@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b06'),
('Shopper 7', 'shopper.7@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b07'),
('Shopper 8', 'shopper.8@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b08'),
('Shopper 9', 'shopper.9@example.com', '3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a5b09')
) AS v(full_name, email, auth_subject)
WHERE NOT EXISTS (SELECT 1 FROM customers c WHERE c.email = v.email);
