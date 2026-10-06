-- V16 (Plan 3 F1, TR-O2/O3, TR-X4): records which fulfilment partner claimed an order.
-- Standard SQL for PostgreSQL and local H2.

ALTER TABLE orders ADD COLUMN assigned_partner VARCHAR(64);
ALTER TABLE orders ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE;

-- Orders already past PLACED predate claiming: give them a placeholder partner so the check below holds.
UPDATE orders SET assigned_partner = 'legacy', claimed_at = updated_at
 WHERE status IN ('CONFIRMED', 'PARCELED', 'DELIVERING', 'DELIVERED');

-- A fulfilled order always has a partner. A partner is not forbidden on CANCELLED (a claimed order may be cancelled).
ALTER TABLE orders ADD CONSTRAINT chk_orders_fulfilled_has_partner
    CHECK (status NOT IN ('CONFIRMED', 'PARCELED', 'DELIVERING', 'DELIVERED') OR assigned_partner IS NOT NULL);
