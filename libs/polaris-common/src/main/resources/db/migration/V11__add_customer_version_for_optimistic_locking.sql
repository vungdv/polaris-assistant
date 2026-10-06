-- V11__add_customer_version_for_optimistic_locking.sql
-- Adds optimistic locking version column to customers table.

ALTER TABLE customers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
