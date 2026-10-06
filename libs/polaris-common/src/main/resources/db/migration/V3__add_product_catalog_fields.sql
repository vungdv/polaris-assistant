ALTER TABLE products ADD COLUMN description VARCHAR(500);
ALTER TABLE products ADD COLUMN category VARCHAR(50);
ALTER TABLE products ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE products SET 
    description = 'High-fidelity wireless earbuds with active noise cancellation and 24-hour battery life.',
    category = 'Audio'
WHERE sku = 'NG-EARBUD-01';

UPDATE products SET 
    description = 'Advanced smartwatch with heart rate monitoring, GPS tracking, and AMOLED display.',
    category = 'Wearables'
WHERE sku = 'NG-WATCH-01';

UPDATE products SET 
    description = 'Portable Bluetooth speaker with 360-degree sound and IPX7 water resistance.',
    category = 'Audio'
WHERE sku = 'NG-SPEAKER-01';

UPDATE products SET 
    description = 'Ultra-compact 65W GaN fast charger with dual USB-C and USB-A ports.',
    category = 'Accessories'
WHERE sku = 'NG-CHARGER-01';

UPDATE products SET 
    description = 'Shockproof slim protective phone case with scratch-resistant matte finish.',
    category = 'Accessories'
WHERE sku = 'NG-CASE-01';

-- Seed an out-of-stock product to test availability filtering
INSERT INTO products (sku, name, price, stock_qty, description, category, is_active) VALUES
('NG-STAND-01', 'Nova Aluminum Laptop Stand', 34.90, 0, 'Ergonomic foldable aluminum laptop stand with heat dissipation.', 'Accessories', TRUE);

CREATE INDEX idx_products_sku ON products(sku);
CREATE INDEX idx_products_category ON products(category);
