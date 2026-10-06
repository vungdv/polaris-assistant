-- V15_1__add_order_version_for_optimistic_locking.sql
-- Plan 1 E4 (TR-X8): Order is versioned so that transactions raising events for the same order are
-- mutually exclusive; a concurrent loser rolls back together with the event it recorded.
-- Forward-only sub-version of Plan 1's reserved V15 (TR-X4); standard SQL for PostgreSQL and local H2.

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
