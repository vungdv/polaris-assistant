-- V13__order_number_sequence.sql
-- Order numbers come from a database sequence (plan S4 / G10) instead of a per-JVM in-memory
-- counter seeded from the clock, which could hand out the same number after a restart or on a
-- second replica and then fail on the UNIQUE order_number constraint.
--
-- The service formats the next value as ORD-%06d. The sequence starts at 10,000,000, above the
-- seeded ORD-1001..ORD-1006 and above anything the old counter could produce (clock millis
-- mod 1,000,000 plus increments), so a generated number never collides with an existing one.
-- "ORD-" plus 8+ digits still fits order_number VARCHAR(20).
--
-- Kept to standard SQL so it applies on both PostgreSQL and the local H2 default.

CREATE SEQUENCE order_number_seq START WITH 10000000 INCREMENT BY 1;
