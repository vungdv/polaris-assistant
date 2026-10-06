-- V7__enforce_non_negative_stock.sql
-- Enforce invariant: product stock quantity cannot be negative (prevents overselling at the database layer)
ALTER TABLE products ADD CONSTRAINT chk_products_stock_non_negative CHECK (stock_qty >= 0);
