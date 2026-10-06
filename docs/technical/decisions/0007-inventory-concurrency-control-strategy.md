# ADR-0007: Inventory Concurrency Control Strategy: Pessimistic Locking vs. Optimistic Locking vs. Atomic Conditional Updates

* Status: Accepted
* Deciders: Polaris Fleet Coordinator, Fleet Architect, Core Platform Engineering
* Date: 2026-09-10
* Technical Story: Resolution of the concurrency control and locking strategy for inventory mutations (`updateInventoryById`, `adjustInventoryById`, `deductStock`, `restoreStock`) in `ProductService.java` to prevent race conditions, lost updates, and inventory overselling in high-concurrency e-commerce environments.

---

## Context and Problem Statement

In the Polaris e-commerce platform, inventory updates are subject to concurrent operations from multiple channels:
1. **Direct Administrative & Supply Chain Adjustments:** Warehouse operators updating stock counts via `PUT /api/v1/products/{id}/inventory` (`updateInventoryById`) or `PUT /api/v1/products/sku/{sku}/inventory`.
2. **Order Checkout Ingress:** Concurrent customer checkouts or AI shopping assistant workflows attempting to reserve or deduct stock (`deductStock`).
3. **Cancellations & Returns:** Order cancellations restoring stock quantities (`restoreStock`).

In [`ProductService.java`](../../../modules/polaris-catalog/src/main/java/vn/danang/polaris/service/ProductService.java), the initial prototype contained a comment:
```java
//TODO: Consider using a database-level lock or optimistic locking to prevent race conditions in a concurrent environment
```

Without an explicit, coordinated locking strategy, two concurrent requests executing a read-modify-write cycle (e.g., read stock 10, calculate new stock, save) can cause the second write to overwrite the first, resulting in **lost updates**, **phantom inventory**, or **negative stock quantities** (violating business invariants).

How should Polaris architect concurrency control for inventory operations to guarantee consistency, prevent race conditions, uphold business invariants, and maintain predictable system throughput?

---

## Decision Drivers

* **Data Consistency & Invariant Guarantees ([AGENTS.md: Principle 1.4 & 2.1](../../../AGENTS.md)):** Inventory counts must never become negative (`stockQty >= 0`), and absolute updates must never silently overwrite interleaved updates without detection or serialization.
* **Simplicity via Lower-Layer Protocol & DB Alignment ([AGENTS.md: Principle 1.1](../../../AGENTS.md)):** Align with standard relational database mechanisms (PostgreSQL row-level locking) rather than introducing bespoke distributed lock layers (e.g. Redlock/ZooKeeper) or complex retry loops.
* **Domain Validation & Entity Lifecycle Integration ([AGENTS.md: Principle 2.1](../../../AGENTS.md)):** The strategy must allow business logic execution (availability status recalculation, threshold notifications, validation) within the domain service before flushing changes.
* **Client & AI Agent Ergonomics (RFC 7807) ([AGENTS.md: Principle 1.1](../../../AGENTS.md)):** Prevent intermittent failures and avoid exposing unnecessary concurrency retry storms to upstream API clients or AI agents.
* **Performance & Deadlock Avoidance:** Ensure locking mechanisms hold locks for the minimal possible duration and avoid deadlocks across multiple resources.

---

## Considered Approaches

We evaluated three architectural approaches for inventory concurrency control:

1. **Approach 1 (Selected): Pessimistic Write Locking (`LockModeType.PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`)**
2. **Approach 2 (Alternative A): Optimistic Locking (`@Version` Attribute / `OptimisticLockException`)**
3. **Approach 3 (Alternative B): Atomic In-Database Conditional Updates / CAS (`@Modifying UPDATE ... WHERE`)**

---

### Approach 1: Pessimistic Write Locking (`LockModeType.PESSIMISTIC_WRITE`)

Acquire an exclusive database row lock at read time within a transactional boundary using JPA's `LockModeType.PESSIMISTIC_WRITE`, translated by Hibernate to `SELECT ... FOR UPDATE` in PostgreSQL:

```java
@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
Optional<Product> findByIdForUpdate(@Param("id") Long id);
```

In `ProductService.java`:
```java
@Transactional
public Product adjustInventoryById(Long id, int delta) {
    Product product = productRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    int current = product.getStockQty() != null ? product.getStockQty() : 0;
    int target = current + delta;
    if (target < 0) {
        throw new IllegalArgumentException("Cannot adjust stock below 0. Current: " + current + ", delta: " + delta);
    }
    product.setStockQty(target);
    return productRepository.save(product);
}
```

* **Good, because** it strictly serializes concurrent read-modify-write cycles at the database level. Any concurrent transaction targeting the same product row waits until the active transaction commits or rolls back.
* **Good, because** it guarantees 100% elimination of lost updates and race conditions without requiring retry loops in the application layer.
* **Good, because** it operates directly on the JPA entity, allowing standard lifecycle management, validation, dirty checking, and returning the updated `ProductResponse` immediately.
* **Good, because** lock scope is tightly bounded: locking by primary key (`id`) or unique indexed column (`sku`) targets exactly one row.
* **Bad, because** concurrent requests targeting the exact same product wait synchronously in DB connection pools, which can increase latency under high-contention spikes if transactions are not kept short.

---

### Approach 2: Optimistic Locking (`@Version` Column / `OptimisticLockException`)

Introduce a `@Version` column (`private Long version;`) on the `Product` entity. Hibernate automatically verifies that the version in the database matches the entity version at update time (`UPDATE products SET ..., version = version + 1 WHERE id = ? AND version = ?`). If a conflict occurs, Hibernate throws an `OptimisticLockException` (Spring `ObjectOptimisticLockingFailureException`).

```java
@Entity
@Table(name = "products")
public class Product {
    ...
    @Version
    private Long version;
}
```

* **Good, because** reads are completely non-blocking; no database row locks are held during the read-process cycle.
* **Good, because** it delivers high throughput when write contention is low (infrequent concurrent updates to the same product).
* **Good, because** it prevents deadlocks across multiple resources.
* **Bad, because** under high write contention (e.g., promotional flash sales or rapid administrative restocking), concurrent transactions will fail with optimistic lock exceptions.
* **Bad, because** to avoid dropping customer transactions, the application layer must implement retry mechanisms (e.g., `@Retryable` with exponential backoff and jitter), introducing substantial architectural complexity, thread churn, and increased database connection pressure.
* **Bad, because** requires Flyway schema migrations across existing database tables to add `version` columns.

---

### Approach 3: Atomic In-Database Conditional Updates / CAS (`@Modifying UPDATE ... WHERE`)

Bypass entity retrieval and perform direct in-database atomic arithmetic updates using SQL `UPDATE ... WHERE`:

```java
@Modifying
@Query("UPDATE Product p SET p.stockQty = :newQuantity WHERE p.id = :id")
int updateInventoryDirect(@Param("id") Long id, @Param("newQuantity") int newQuantity);

@Modifying
@Query("UPDATE Product p SET p.stockQty = p.stockQty + :delta WHERE p.id = :productId AND p.stockQty + :delta >= 0")
int adjustStockIfAvailable(@Param("productId") Long productId, @Param("delta") int delta);
```

* **Good, because** it provides the highest raw database throughput. The row lock is held only for the microsecond duration of the single SQL `UPDATE` statement.
* **Good, because** it completely avoids application-level locks and retry loops for relative delta increments/decrements.
* **Bad, because** it bypasses the JPA `EntityManager` First-Level Cache and entity lifecycle. If the persistence context already holds the entity, it becomes stale unless manually cleared (`clearAutomatically = true`) or reloaded.
* **Bad, because** for absolute quantity updates (`updateInventoryById(id, newQuantity)`), conditional checks cannot protect against overwriting a newer update without reading the row first.
* **Bad, because** the calling service must issue an extra query to load and return the updated entity for the REST API response (`ProductResponse`).

---

## Comprehensive Trade-Off Matrix

| Evaluation Dimension | Weight | Approach 1: Pessimistic Locking (Selected) | Approach 2: Optimistic Locking (`@Version`) | Approach 3: Atomic Modifying Query |
| :--- | :---: | :---: | :---: | :---: |
| **Data Consistency & Race Prevention** | 30% | **5/5** (Strict row serialization, zero lost updates) | **4/5** (Consistent but throws on collision) | **4/5** (Consistent for deltas, weak for absolute set) |
| **Application Simplicity & Zero Retry Code** | 25% | **5/5** (DB handles queueing; no retry logic) | **2/5** (Requires `@Retryable`, backoff, jitter) | **3/5** (Manual cache synchronization & re-fetch) |
| **Throughput under Moderate Contention** | 20% | **4/5** (Sub-millisecond row lock on PK) | **2/5** (High abort/retry rates under contention) | **5/5** (Max throughput, microsecond statement lock) |
| **JPA / Domain Lifecycle Integration** | 15% | **5/5** (Full entity lifecycle, dirty check, DTO mapping) | **5/5** (Full entity lifecycle) | **2/5** (Bypasses lifecycle; L1 cache staleness) |
| **Implementation Scope & Schema Impact** | 10% | **5/5** (Zero DDL changes; already indexed on PK/SKU) | **2/5** (Requires Flyway DDL migration for `version`) | **4/5** (Zero DDL changes) |
| **Weighted Total Score** | **100%** | **4.65 / 5.0** | **3.05 / 5.0** | **3.65 / 5.0** |

*Scoring Calculations:*
* **Approach 1:** `(0.30 × 5) + (0.25 × 5) + (0.20 × 4) + (0.15 × 5) + (0.10 × 5) = 1.50 + 1.25 + 0.80 + 0.75 + 0.50 = 4.80`
* **Approach 2:** `(0.30 × 4) + (0.25 × 2) + (0.20 × 2) + (0.15 × 5) + (0.10 × 2) = 1.20 + 0.50 + 0.40 + 0.75 + 0.20 = 3.05`
* **Approach 3:** `(0.30 × 4) + (0.25 × 3) + (0.20 × 5) + (0.15 × 2) + (0.10 × 4) = 1.20 + 0.75 + 1.00 + 0.30 + 0.40 = 3.65`

---

## Decision Outcome

Polaris adopts **Approach 1: Pessimistic Write Locking (`LockModeType.PESSIMISTIC_WRITE`)** combined with **Pure Relative Delta Adjustments** for inventory mutations:

1. **Relative Delta-Only API Standardization:**
   - Real-world physical operations (goods receipt, warehouse arrivals, cycle counts, write-offs) are inherently delta-based.
   - The API for setting absolute stock (`quantity`) was intentionally removed. Both `PUT /api/v1/products/{id}/inventory` and `PUT /api/v1/products/sku/{sku}/inventory` now strictly accept `{"delta": <integer>}`.
   - This simplifies client and UI integration (showing current quantity with an input to add or deduct) and prevents stale absolute overwrite bugs.

2. **ID-Based Mutation (`ProductService.adjustInventoryById(Long id, int delta)`):**
   - Uses `productRepository.findByIdForUpdate(id)` which executes `SELECT ... WHERE p.id = :id FOR UPDATE`.
   - Acquires an exclusive row lock, calculates `target = currentStock + delta`, enforces `target >= 0`, updates `stockQty`, and persists within the `@Transactional` boundary.

3. **SKU-Based Mutation (`ProductService.adjustInventory(String sku, int delta)`):**
   - Consistently utilizes `productRepository.findBySkuIgnoreCaseForUpdate(sku)` for uniform row-level locking.
   - `deductStock` and `restoreStock` also leverage pessimistic locking by SKU.

4. **Specialized High-Throughput Primitives:**
   - The atomic direct queries (`decrementStockIfAvailable`, `incrementStock`) in `ProductRepository` remain available for high-throughput headless batch jobs where entity lifecycle hydration is unneeded.

---

## Consequences

### Positive Consequences
* **Deterministic Concurrency:** No race conditions, no lost updates, and no phantom inventory. Concurrent requests are serialized cleanly at the database layer.
* **Zero Client Retry Burden:** Upstream HTTP clients and AI agents interacting via REST or MCP do not receive transient optimistic lock conflict errors (`409 Conflict`), eliminating client retry complexity.
* **Pure Domain Logic:** Business invariant checks (non-negative stock, active flag) occur safely in Java memory with guaranteed exclusive row access during the transaction.
* **Zero Schema Migrations:** No additional `@Version` column or DDL migration required.

### Negative / Operational Safeguards
* **Lock Duration:** Transactions containing pessimistic write locks must remain lean and perform zero external network I/O, slow queries, or thread sleeping while holding the connection.
* **Deadlock Prevention:** All inventory locks are acquired on single rows via primary key (`id`) or unique natural key (`sku`). If multi-item checkout is implemented in future slices, rows must be locked in a deterministic sort order (e.g. by ascending product ID) to prevent circular deadlocks.
