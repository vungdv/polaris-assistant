-- V10__add_extended_customer_profile_fields.sql
-- Adds extended profile, address, contact, and account attributes to customers table.

ALTER TABLE customers ADD COLUMN first_name VARCHAR(100);
ALTER TABLE customers ADD COLUMN last_name VARCHAR(100);
ALTER TABLE customers ADD COLUMN secondary_email VARCHAR(150);
ALTER TABLE customers ADD COLUMN mobile_phone VARCHAR(30);
ALTER TABLE customers ADD COLUMN date_of_birth VARCHAR(20);
ALTER TABLE customers ADD COLUMN gender VARCHAR(20);
ALTER TABLE customers ADD COLUMN avatar_url VARCHAR(500);
ALTER TABLE customers ADD COLUMN company VARCHAR(150);
ALTER TABLE customers ADD COLUMN job_title VARCHAR(100);
ALTER TABLE customers ADD COLUMN department VARCHAR(100);
ALTER TABLE customers ADD COLUMN tax_id VARCHAR(50);
ALTER TABLE customers ADD COLUMN billing_address_line1 VARCHAR(255);
ALTER TABLE customers ADD COLUMN billing_address_line2 VARCHAR(255);
ALTER TABLE customers ADD COLUMN billing_city VARCHAR(100);
ALTER TABLE customers ADD COLUMN billing_state VARCHAR(100);
ALTER TABLE customers ADD COLUMN billing_postal_code VARCHAR(30);
ALTER TABLE customers ADD COLUMN billing_country VARCHAR(100);
ALTER TABLE customers ADD COLUMN shipping_address_line1 VARCHAR(255);
ALTER TABLE customers ADD COLUMN shipping_address_line2 VARCHAR(255);
ALTER TABLE customers ADD COLUMN shipping_city VARCHAR(100);
ALTER TABLE customers ADD COLUMN shipping_state VARCHAR(100);
ALTER TABLE customers ADD COLUMN shipping_postal_code VARCHAR(30);
ALTER TABLE customers ADD COLUMN shipping_country VARCHAR(100);
ALTER TABLE customers ADD COLUMN customer_tier VARCHAR(50) DEFAULT 'STANDARD';
ALTER TABLE customers ADD COLUMN status VARCHAR(50) DEFAULT 'ACTIVE';
ALTER TABLE customers ADD COLUMN notes VARCHAR(1000);
ALTER TABLE customers ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Backfill Alice Tran with rich sample profile data
UPDATE customers SET
    first_name = 'Alice',
    last_name = 'Tran',
    secondary_email = 'alice.personal@example.com',
    mobile_phone = '0901111199',
    date_of_birth = '1992-05-14',
    gender = 'Female',
    company = 'Danang Tech Solutions',
    job_title = 'Senior Software Engineer',
    department = 'Engineering',
    billing_address_line1 = '123 Bach Dang St',
    billing_city = 'Da Nang',
    billing_country = 'Vietnam',
    shipping_address_line1 = '123 Bach Dang St',
    shipping_city = 'Da Nang',
    shipping_country = 'Vietnam',
    customer_tier = 'GOLD',
    status = 'ACTIVE'
WHERE id = 1;
