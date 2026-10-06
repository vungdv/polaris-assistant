# PRD-007: Product Creation and Inventory Management Capabilities

- **Target Context/Theme:** Catalog / Inventory / Store Operations (Theme 1: Product Catalog & Discovery, Inventory Management)
- **Target Persona:** Store Administrator (`ROLE_ADMIN`), Catalog Manager (`ROLE_STAFF`), Warehouse / Inventory Manager (`ROLE_STAFF`), Autonomous AI Assistant
- **Status:** PRD Approved (Ready for Architecture & Engineering)
- **Document Owner:** Polaris Product Manager
- **Last Updated:** 2026-09-10

---

## 1. Problem Statement & Value Proposition

### 1.1 Context & Background
Polaris currently provides robust read-only product discovery (`GET /api/v1/products`, keyword search, category hierarchy filtering, price boundaries, and stock availability checks), single product inspection by database ID (`GET /api/v1/products/{id}`) and SKU (`GET /api/v1/products/sku/{sku}`), and an inventory update endpoint addressing products by SKU (`PUT /api/v1/products/sku/{sku}/inventory`).

### 1.2 Pain Points & Operational Gaps
1. **No Product Onboarding API:** Store operators and catalog managers cannot onboard new merchandise through standard REST APIs. Introducing new products currently requires database migrations, seeding scripts, or direct SQL execution, creating severe operational friction.
2. **Missing Inventory Adjustment by Product ID:** Warehouse management systems (WMS), ERP connectors, and barcode/RFID scanning pipelines typically key inventory records by internal database numeric IDs (`id`). While Polaris supports SKU-based updates, the lack of an ID-based inventory adjustment endpoint (`PUT /api/v1/products/{id}/inventory`) creates integration friction and breaks architectural symmetry.
3. **AI Agent Tooling Gap:** Autonomous AI shopping and back-office agents acting as catalog administrators or inventory reconcilers cannot onboard newly negotiated catalog items or adjust stock counts without comprehensive API primitives.
4. **Data Integrity & Consistency Risks:** Without formal API-level enforcement of SKU uniqueness, category validation, price positivity, and non-negative inventory invariants, direct data manipulations risk introducing catalog anomalies.
5. **Standardized RFC 7807 Problem Details:** Client applications and conversational agents require actionable, machine-readable error responses (distinguishing 400 validation failures, 404 resource omissions, and 409 duplicate SKU conflicts) with concrete remediation advice.

### 1.3 Value Proposition
Delivering this Product Management and Inventory initiative completes core store administration capabilities by:
- Providing an intuitive, validated REST API (`POST /api/v1/products`) for immediate product creation with HTTP 201 Created semantics and Location headers.
- Delivering full functional parity for inventory management across both primary identifiers: database ID (`PUT /api/v1/products/{id}/inventory`) and business SKU (`PUT /api/v1/products/sku/{sku}/inventory`), supporting both absolute stock overrides and relative delta adjustments.
- Enforcing domain invariants: non-negative inventory, strictly positive pricing, and case-insensitive SKU uniqueness.
- Returning RFC 7807 Problem Details with remediation hints for validation failures (400), non-existent products/categories (404), and duplicate SKU collisions (409).

---

## 2. User Personas & Primary Use Cases

### Persona A: Catalog Manager / Merchandiser (`ROLE_STAFF`, `ROLE_ADMIN`)
Responsible for managing the store's assortment, introducing seasonal product lines, categorizing merchandise, and setting initial retail prices and stock allocations.

### Persona B: Warehouse / Inventory Manager (`ROLE_STAFF`)
Responsible for physical goods receipt, stock counts, damaged goods write-offs, and stock reconciliations using warehouse handheld devices that scan product IDs or barcodes.

### Persona C: Store Administrator (`ROLE_ADMIN`)
Oversees the technical integration of third-party ERP/PIM systems and ensures strict catalog data integrity, auditing, and role-based permissions.

### Persona D: Autonomous AI Assistant / Store Operations Agent
An AI-driven operational assistant performing automated catalog onboarding and inventory restocking via structured tool calls on behalf of authorized store personnel.

### Primary Use Cases:
1. **UC-1: Onboard a New Product Line:**
   - A catalog manager receives new seasonal stock (`NG-HEADSET-PRO`).
   - Submits product details (SKU, Name, Description, Category ID, Price, Initial Stock).
   - System validates uniqueness, persists the product, sets availability, and returns HTTP 201 Created with a `Location: /api/v1/products/{id}` header.
2. **UC-2: Warehouse Goods Receipt by Product ID:**
   - A shipment of 50 units arrives at the warehouse dock. The receiving scanner reads internal product ID `42`.
   - The warehouse app sends a relative delta adjustment (`delta = +50`) to `/api/v1/products/42/inventory`.
   - The stock increases atomically from 120 to 170 units without needing to resolve the SKU.
3. **UC-3: Cycle Count Inventory Overwrite by Product ID:**
   - An inventory auditor conducts a physical audit of product ID `15` and discovers exactly 38 units on the shelf (system recorded 40).
   - The auditor sends an absolute stock override (`quantity = 38`).
   - The system sets stock directly to 38 and updates availability accordingly.
4. **UC-4: Shrinkage / Damage Adjustment with Invariant Guard:**
   - An operator attempts to write off 15 damaged units of product ID `7`, but only 10 units are currently recorded in stock.
   - The system rejects the adjustment with an RFC 7807 Problem Detail explaining that stock cannot drop below zero.
5. **UC-5: Duplicate SKU Detection During System Sync:**
   - An ERP synchronization job attempts to create a product with SKU `NG-EARBUD-01`, which already exists in the catalog.
   - The system halts creation and returns an RFC 7807 `409 Conflict` response with the colliding SKU and remediation guidance.

---

## 3. Business Rules & Functional Requirements

### FR-1: Product Creation (`POST /api/v1/products`)
- **Endpoint:** `POST /api/v1/products`
- **Required Fields:**
  - `sku`: String, mandatory, trimmed, non-blank (e.g. `"NG-SMARTWATCH-02"`). Must be unique across the entire catalog (case-insensitive comparison).
  - `name`: String, mandatory, trimmed, non-blank (e.g. `"Nova Smartwatch Pro 2"`). Maximum 255 characters.
  - `price`: Decimal number, mandatory, strictly positive (`price > 0.00`). Example: `199.99`.
- **Optional Fields:**
  - `description`: String, optional, multi-line allowed.
  - `category`: String, optional. Category name or label.
  - `categoryId`: Long, optional. Numeric identifier linking to an existing category in the category hierarchy. If supplied, the system must verify that a category with this ID exists.
  - `stockQuantity` (or `stockQty`): Integer, optional. Defaults to `0` if not provided. Must be non-negative (`>= 0`).
  - `active` (or `isActive`): Boolean, optional. Defaults to `true` if omitted.
- **Validation Rules & Invariants:**
  - Price must be greater than zero (`price > 0.00`). Zero or negative price values must be rejected.
  - Initial stock quantity must be non-negative (`stock >= 0`). Negative values must be rejected.
  - SKU must be unique across the catalog. If a product with the same SKU (case-insensitive) already exists, the request must be rejected.
  - If `categoryId` is specified, the category must exist in the database. If not found, the request must be rejected.
- **Success Semantics:**
  - HTTP Status: `201 Created`
  - Response Header: `Location: /api/v1/products/{id}` pointing to the newly created product resource.
  - Response Body: Complete `ProductResponse` representation including assigned numeric `id`, `sku`, `name`, `description`, `category`, `categoryId`, `price`, `stockQuantity`, `isAvailable`, `active`, and `createdAt` timestamp.
  - Dynamic Availability: `isAvailable` must evaluate to `true` if `stockQuantity > 0` and `active == true`; otherwise `false`.

### FR-2: Inventory Update / Adjustment by ID (`PUT /api/v1/products/{id}/inventory`)
- **Endpoint:** `PUT /api/v1/products/{id}/inventory`
- **Parity with SKU-Based Endpoint:** Must provide identical operational capabilities and business guarantees as the existing `PUT /api/v1/products/sku/{sku}/inventory` endpoint.
- **Payload Modes (Dual-Mode Operation):**
  - **Absolute Mode (`quantity`):** Sets the absolute in-stock inventory count directly. Value must be an integer `>= 0`.
  - **Relative Mode (`delta`):** Increments or decrements the current stock level by an integer value (positive to restock, negative for deduction/shrinkage).
  - **Payload Validation:** The request must provide either `quantity` or `delta`. Providing neither, or providing contradictory invalid inputs, must be rejected with HTTP 400.
- **Non-Negative Invariant Guard:**
  - Inventory quantity can never drop below zero.
  - For relative adjustments: if `currentStock + delta < 0`, the operation must be rejected without altering the stored stock.
- **Concurrency & Pessimistic Locking:**
  - All inventory reads and mutations within the transaction must use pessimistic write locking (`PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`) to eliminate race conditions and guarantee atomic updates under high concurrency.
- **Success Semantics:**
  - HTTP Status: `200 OK`
  - Response Body: Updated `ProductResponse` reflecting the new `stockQuantity` and recalculated `isAvailable` status.

### FR-3: Inventory Update / Adjustment by SKU Parity (`PUT /api/v1/products/sku/{sku}/inventory`)
- **Endpoint:** `PUT /api/v1/products/sku/{sku}/inventory`
- Maintains exact functional and validation parity with the ID-based endpoint.
- If the target SKU does not exist (case-insensitive lookup), returns RFC 7807 `404 Not Found`.

### FR-4: Standardized Error Feedback (RFC 7807 Problem Details)
All error responses must use `application/problem+json` media type and provide machine-actionable problem details:
1. **Validation Failures (`400 Bad Request`):**
   - Triggered when mandatory fields are missing, price <= 0, stock < 0, or neither `quantity` nor `delta` is provided.
   - `type`: `https://polaris.local/errors/validation-error` or `https://polaris.local/errors/bad-request`
   - `title`: `"Validation Error"` or `"Bad Request"`
   - `status`: `400`
   - Properties: `invalid_param`, `received`, `remedy`, and an `errors` list detailing each field failure.
2. **Duplicate SKU Conflict (`409 Conflict`):**
   - Triggered when creating a product whose SKU already exists in the catalog (case-insensitive).
   - `type`: `https://polaris.local/errors/duplicate-sku` or `https://polaris.local/errors/conflict`
   - `title`: `"Duplicate SKU Conflict"`
   - `status`: `409`
   - Properties: `sku`, `detail`: `"A product with SKU '{sku}' already exists."`, `remedy`: `"Choose a unique SKU code or update the existing product."`
3. **Product Not Found (`404 Not Found`):**
   - Triggered when updating inventory for a non-existent product ID or SKU.
   - `type`: `https://polaris.local/errors/not-found`
   - `title`: `"Resource Not Found"`
   - `status`: `404`
   - Properties: `detail`: `"Product not found with id: {id}"` or `"Product not found with SKU: {sku}"`, `remedy`: `"Verify the product identifier before retrying."`
4. **Category Not Found (`404 Not Found`):**
   - Triggered when creating a product with a `categoryId` that does not exist.
   - `type`: `https://polaris.local/errors/not-found`
   - `title`: `"Resource Not Found"`
   - `status`: `404`
   - Properties: `detail`: `"Category not found with id: {categoryId}"`, `remedy`: `"Provide a valid existing category ID from the category catalog."`
5. **Negative Stock Adjustment (`400 Bad Request`):**
   - Triggered when a delta adjustment would reduce stock below zero.
   - `type`: `https://polaris.local/errors/bad-request` or `https://polaris.local/errors/invalid-stock-adjustment`
   - `title`: `"Bad Request"` or `"Invalid Stock Adjustment"`
   - `status`: `400`
   - Properties: `detail`: `"Cannot adjust stock below 0. Current: {current}, delta: {delta}"`, `current_stock`, `delta`, `remedy`: `"Adjust delta to be greater than or equal to -{current}."`

---

## 4. Business Acceptance Criteria (Given / When / Then)

### Scenario 1: Product Creation with Required Fields Only
- **Given** an authorized user with product creation privileges.
- **When** the user submits a `POST /api/v1/products` request with:
  ```json
  {
    "sku": "NG-KEYBOARD-01",
    "name": "Nova Mechanical Keyboard",
    "price": 89.99
  }
  ```
- **Then** the server responds with `HTTP 201 Created`.
- **And** the `Location` header is present with value matching `/api/v1/products/{id}`.
- **And** the response body contains:
  - `sku`: `"NG-KEYBOARD-01"`
  - `name`: `"Nova Mechanical Keyboard"`
  - `price`: `89.99`
  - `stockQuantity`: `0`
  - `isAvailable`: `false` (since stock is 0)
  - `active`: `true`
  - Generated numeric `id` and `createdAt` timestamp.

### Scenario 2: Product Creation with Full Optional Fields & Category Association
- **Given** Category `"Audio"` with ID `1` exists in the system.
- **When** the user submits a `POST /api/v1/products` request with:
  ```json
  {
    "sku": "NG-SPEAKER-PRO",
    "name": "Nova SoundCore Portable Speaker",
    "description": "High-fidelity Bluetooth speaker with 24-hour battery life",
    "categoryId": 1,
    "price": 129.50,
    "stockQuantity": 75,
    "active": true
  }
  ```
- **Then** the server responds with `HTTP 201 Created`.
- **And** the response body contains `id`, `categoryId`: `1`, `category`: `"Audio"`, `stockQuantity`: `75`, and `isAvailable`: `true`.
- **And** the `Location` header matches `/api/v1/products/{id}`.

### Scenario 3: Product Creation Rejection - Duplicate SKU Conflict
- **Given** a product with SKU `"NG-EARBUD-01"` already exists in the catalog.
- **When** a user submits a `POST /api/v1/products` request with SKU `"ng-earbud-01"` or `"NG-EARBUD-01"`.
- **Then** the server rejects the request with `HTTP 409 Conflict`.
- **And** the response media type is `application/problem+json`.
- **And** the problem detail contains `title`: `"Duplicate SKU Conflict"`, `type`: `"https://polaris.local/errors/duplicate-sku"`, and `sku`: `"NG-EARBUD-01"`.
- **And** no duplicate database record is created.

### Scenario 4: Product Creation Rejection - Validation Failures (Missing Name, Non-Positive Price, Negative Stock)
- **Given** an authorized user creating a product.
- **When** the user submits a `POST /api/v1/products` request with missing `sku`, blank `name`, `price: -10.00`, and `stockQuantity: -5`.
- **Then** the server rejects the request with `HTTP 400 Bad Request`.
- **And** the response body is an RFC 7807 Problem Detail containing `title`: `"Validation Error"` and an `errors` collection listing each violated field constraint.

### Scenario 5: Product Creation Rejection - Non-Existent Category ID
- **Given** no category with ID `9999` exists in the catalog.
- **When** the user submits a `POST /api/v1/products` request with `categoryId: 9999`.
- **Then** the server rejects the request with `HTTP 404 Not Found`.
- **And** the response problem details explain that category `9999` was not found, with remedy instructions to select an existing category ID.

### Scenario 6: Inventory Update by Product ID - Absolute Quantity Set
- **Given** product with ID `1` exists with current stock `10`.
- **When** the user submits `PUT /api/v1/products/1/inventory` with:
  ```json
  {
    "quantity": 50
  }
  ```
- **Then** the server responds with `HTTP 200 OK`.
- **And** the returned `ProductResponse` has `id`: `1`, `stockQuantity`: `50`, and `isAvailable`: `true`.

### Scenario 7: Inventory Adjustment by Product ID - Relative Delta (Restock)
- **Given** product with ID `2` exists with current stock `20`.
- **When** the user submits `PUT /api/v1/products/2/inventory` with:
  ```json
  {
    "delta": 30
  }
  ```
- **Then** the server responds with `HTTP 200 OK`.
- **And** the product stock count increases to `50` (`20 + 30`).
- **And** `isAvailable` remains `true`.

### Scenario 8: Inventory Adjustment by Product ID - Relative Delta (Write-off / Decrement)
- **Given** product with ID `2` exists with current stock `50`.
- **When** the user submits `PUT /api/v1/products/2/inventory` with:
  ```json
  {
    "delta": -15
  }
  ```
- **Then** the server responds with `HTTP 200 OK`.
- **And** the product stock count decreases to `35` (`50 - 15`).

### Scenario 9: Inventory Adjustment by Product ID - Rejection on Negative Resulting Stock
- **Given** product with ID `3` exists with current stock `5`.
- **When** the user submits `PUT /api/v1/products/3/inventory` with:
  ```json
  {
    "delta": -10
  }
  ```
- **Then** the server rejects the request with `HTTP 400 Bad Request`.
- **And** the RFC 7807 response explains that stock cannot drop below zero (`current: 5, delta: -10`).
- **And** the product's stock remains untouched at `5`.

### Scenario 10: Inventory Update by Product ID - Product Not Found
- **Given** no product exists with ID `9999`.
- **When** the user submits `PUT /api/v1/products/9999/inventory` with `quantity: 20`.
- **Then** the server responds with `HTTP 404 Not Found`.
- **And** the response is an RFC 7807 Problem Detail with `title`: `"Resource Not Found"`.

### Scenario 11: Inventory Update by Product ID - Missing Quantity and Delta
- **Given** product with ID `1` exists.
- **When** the user submits `PUT /api/v1/products/1/inventory` with empty payload `{}`.
- **Then** the server rejects the request with `HTTP 400 Bad Request`.
- **And** the error details indicate that either `quantity` or `delta` must be provided.

### Scenario 12: Dynamic Availability Status Update on Inventory Zeroing
- **Given** product with ID `4` is active and has `stockQuantity: 10` with `isAvailable: true`.
- **When** the user submits `PUT /api/v1/products/4/inventory` with `quantity: 0`.
- **Then** the server responds with `HTTP 200 OK`.
- **And** `stockQuantity` is `0`.
- **And** `isAvailable` dynamically transitions to `false`.

### Scenario 13: Functional Parity Between SKU-Based and ID-Based Inventory Endpoints
- **Given** product with ID `5` and SKU `"NG-CHARGER-01"` has `stockQuantity: 25`.
- **When** user updates inventory via `PUT /api/v1/products/5/inventory` with `delta: 10`.
- **Then** the stock becomes `35`.
- **When** user subsequent update occurs via `PUT /api/v1/products/sku/NG-CHARGER-01/inventory` with `delta: 15`.
- **Then** the stock becomes `50`.
- **And** both endpoints return identical `ProductResponse` structures and enforce identical validation and locking semantics.

---

## 5. Out of Scope

1. **Full Product Catalog Updates (`PUT /api/v1/products/{id}` or `PATCH`):** Mutating product names, descriptions, categories, or prices on existing products is deferred to a subsequent catalog maintenance PRD.
2. **Product Deletion or Archival (`DELETE /api/v1/products/{id}`):** Soft-deletion, catalog archiving, and deactivation workflows are out of scope for this milestone.
3. **Multi-Warehouse / Multi-Location Inventory Allocation:** Stock remains modeled as a single unified available count per product; multi-location inventory partitioning is out of scope.
4. **Batch / Bulk Import/Export APIs:** CSV/Excel ingestion, batch catalog upload pipelines, and asynchronous ETL jobs are deferred to a separate integration milestone.
5. **Pricing History & Promotional Schedules:** Audit trails for price adjustments and time-based discount schedules are out of scope for this API version.
