-- V9__add_customer_fullname_trgm_index.sql
-- Adds index support for customer full_name fuzzy/substring search.
--
-- H2 (test): Standard B-tree index applied automatically via Flyway.
-- PostgreSQL (production DBA note — run outside Flyway after migration):
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX idx_customers_fullname_trgm ON customers USING GIN (full_name gin_trgm_ops);
-- The GIN trigram index provides sub-millisecond similarity() queries at scale.
CREATE INDEX IF NOT EXISTS idx_customers_fullname ON customers (full_name);
