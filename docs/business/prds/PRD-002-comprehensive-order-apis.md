# PRD-005: Comprehensive Order Lifecycle Capabilities & AI Shop Agent Integration

- **Target Context/Theme:** Order / Gateway / MCP (Theme 2: Checkout & Order Lifecycle, Theme 3: Autonomous AI Agent Experience)
- **Target Persona:** Autonomous AI Shop Agent, Conversational Shopper, Customer Service Agent
- **Status:** Delivered

---

## 1. Problem Statement & Value Proposition

Polaris currently offers product discovery capabilities (catalog search, SKU lookup, category filtering) and basic order status retrieval and cancellation. However, an **AI Shop Agent** (conversational shopping assistant) cannot currently complete the purchasing loop on behalf of a shopper:
1. There is no external capability to submit and place new multi-item orders.
2. Order placement lacks live inventory stock validation and automated stock deduction, risking overselling.
3. Shoppers and AI agents cannot query customer order history to answer customer inquiries such as *"What orders have I placed this week?"* or *"Track my recent orders"*.
4. AI agents operating autonomously in non-deterministic environments risk creating duplicate orders if network timeouts or tool retry loops occur without idempotency protection.
5. Error responses lack structured, machine-actionable remedies when items are out of stock or quantities are invalid, causing LLM shopping agents to fail rather than negotiate alternatives.

Delivering a comprehensive, AI-ready Order MVP completes the core commerce loop, empowering autonomous AI agents to discover products, negotiate quantities, place orders idempotently with stock protection, track fulfillment history, and manage cancellations.

---

## 2. User Personas & Use Cases

### Persona A: Autonomous AI Shop Agent
An LLM-driven shopping assistant interacting with human customers over conversational channels (chat, voice, IDE/MCP). The agent interprets intent, recommends products, confirms purchase decisions, places orders, and answers order tracking questions.

### Persona B: Conversational Shopper
A retail customer using an AI shopping assistant to buy products, track recent purchases, and cancel orders when plans change.

### Persona C: Customer Support Agent / Store Administrator
A human or automated agent checking customer order records, order status breakdowns, and handling order cancellations.

### Primary Use Cases:
1. **Conversational Multi-Item Purchase:**
   - Shopper tells the AI agent: *"I want to buy 1 Nova Wireless Earbuds and 2 Fast Chargers for Alice Tran."*
   - AI agent verifies availability, reserves stock, and submits the order with idempotency protection.
   - Order is recorded with status `PLACED`, stock is deducted, and the AI agent receives the confirmed order details and order number.
2. **Order History Inquiry:**
   - Shopper asks: *"What orders do I currently have in progress?"*
   - AI agent queries the customer's order history filtered by customer and/or active statuses, receiving a paginated list of matching orders.
3. **Order Status Tracking & Inspection:**
   - Shopper asks: *"What is the status of ORD-1001?"*
   - AI agent retrieves full line items, total pricing, status, and timeline.
4. **Self-Service Cancellation:**
   - Shopper asks: *"Cancel my order ORD-1001."*
   - AI agent requests cancellation. If eligible (`PLACED` or `CONFIRMED`), the order is cancelled, inventory is released/restored, and confirmation is returned.
   - If the order has already shipped or delivered, the agent receives an actionable conflict explanation to inform the customer.

---

## 3. Business Rules & Functional Requirements

### FR-1: Order Creation & Submission
- **Requirement:** Customers or AI agents acting on their behalf must be able to submit a new order containing one or more line items for an identified customer.
- **Line Item Validation:** Each item must specify a valid product identifier/code and a positive quantity (integer >= 1). The order must contain at least one item.
- **Pricing Snapshot:** The unit price recorded for each line item must be captured from the active catalog price at the moment of order placement to prevent price tampering.
- **Total Calculation:** The order total must equal the exact sum of all line item subtotals (`unit_price * quantity`).
- **Initial Status:** Newly placed orders must enter the lifecycle in `PLACED` status.

### FR-2: Real-Time Inventory Stock Check & Deduction
- **Requirement:** Order placement must verify that sufficient in-stock inventory exists for every requested product.
- **Atomic Stock Deduction:** Upon successful order placement, available inventory stock for each purchased item must be immediately decremented by the purchased quantity.
- **Out-of-Stock Guard:** If any requested item has insufficient inventory, the entire order submission must be rejected atomically (no partial orders, no partial stock deduction).
- **Actionable Stock Feedback:** When stock is insufficient, the system must return detailed business diagnostics indicating which specific product is short, how many units were requested, and the maximum quantity currently available.

### FR-3: Idempotent Order Submission
- **Requirement:** The order placement capability must support client-provided idempotency keys to protect autonomous AI agents from duplicate order charges during network retries or LLM re-invocations.
- **Idempotency Guarantee:** Re-submitting an order request with the same idempotency key within an active operational window must safely return the original created order without creating a second order or deducting duplicate inventory.

### FR-4: Customer Order History & Search
- **Requirement:** The system must provide the capability to query and list orders for a given customer.
- **Filtering & Pagination:** Support filtering by customer identifier and optional order status (e.g. `PLACED`, `CONFIRMED`, `DELIVERED`, `CANCELLED`). Support bounded pagination (page index and page size) and chronological sorting (newest first by default).
- **Summary Information:** Results must include order number, status, placement timestamp, total amount, and line item count.

### FR-5: Single Order Inspection & Tracking
- **Requirement:** The system must provide full inspection of a specific order by its business order number (`ORD-XXXXX`), returning customer information, fulfillment status, total amount, timestamp, and full line item details (SKU, product name, quantity, unit price, subtotal).

### FR-6: Order Cancellation & Inventory Restoral
- **Requirement:** Eligible orders in `PLACED` or `CONFIRMED` status may be cancelled.
- **Lifecycle Guard:** Orders in non-cancellable terminal or fulfillment states (e.g. `PARCELED`, `DELIVERING`, `DELIVERED`, or already `CANCELLED`) must be rejected with an explicit business conflict explanation.
- **Inventory Restoral:** Cancelling an order must automatically restock the inventory for all line items in that order.

### FR-7: Autonomous AI Agent (MCP) Tool Suite
- **Requirement:** All core order operations must be exposed as native Model Context Protocol (MCP) tools for AI agents:
  - `place_order`: Create and submit a new multi-item order with customer ID, line items, and optional idempotency key.
  - `get_order_details`: Retrieve full order details and fulfillment status by order number.
  - `list_customer_orders`: Search and list orders for a customer with optional status filter.
  - `cancel_order`: Cancel an eligible order by order number with inventory restoral.
- **Tool Grounding:** Tool descriptions and schemas must be clear and self-documenting to enable LLM tool-calling agents to invoke them reliably.

### FR-8: Self-Correcting AI Error Feedback
- **Requirement:** Any validation failure, inventory shortage, or state conflict must provide machine-readable error properties with explicit remediation guidance so calling AI agents can correct their parameters autonomously on the next attempt.

---

## 4. Business Acceptance Criteria (Given / When / Then)

### Scenario 1: Successful Multi-Item Order Placement with Stock Deduction
- **Given** customer Alice Tran (ID: 1) exists, product `NG-EARBUD-01` has 120 units in stock at $49.90, and `NG-CHARGER-01` has 200 units in stock at $24.90.
- **When** an AI agent submits an order for Alice Tran with 1 unit of `NG-EARBUD-01` and 2 units of `NG-CHARGER-01`.
- **Then** the order is created with status `PLACED`, total amount $99.70, and a unique order number (`ORD-xxxxx`).
- **And** the inventory stock of `NG-EARBUD-01` drops to 119 and `NG-CHARGER-01` drops to 198.

### Scenario 2: Rejection Due to Insufficient Stock with Remediation Guidance
- **Given** product `NG-WATCH-01` has only 5 units in stock.
- **When** an AI agent attempts to place an order for 10 units of `NG-WATCH-01`.
- **Then** the order submission is rejected with an out-of-stock business error.
- **And** the error payload explicitly indicates that `NG-WATCH-01` has only 5 units available, providing a remedy recommending ordering <= 5 units.
- **And** no stock is deducted and no order record is created.

### Scenario 3: Idempotent Order Submission Prevents Duplicate Billing
- **Given** an order submission with idempotency key `idempotency-key-abc-123` is processed successfully.
- **When** the AI agent retries or re-sends the exact same submission with `idempotency-key-abc-123`.
- **Then** the response returns the previously created order details without creating a new order number and without deducting inventory a second time.

### Scenario 4: Query Customer Order History with Pagination
- **Given** customer Alice Tran has multiple historical orders in various statuses.
- **When** the AI agent queries Alice's orders requesting page 0 with size 10.
- **Then** a paginated collection of Alice's orders is returned, ordered newest first, with order number, status, item counts, and totals.

### Scenario 5: Successful Order Cancellation Restores Stock
- **Given** an active order `ORD-1001` in `PLACED` status containing 1 unit of `NG-EARBUD-01`.
- **When** the AI agent or customer requests cancellation of `ORD-1001`.
- **Then** the order status transitions to `CANCELLED`.
- **And** the inventory stock of `NG-EARBUD-01` is incremented by 1.

### Scenario 6: Non-Cancellable Order Conflict
- **Given** order `ORD-1005` is in `DELIVERED` status.
- **When** the AI agent requests cancellation of `ORD-1005`.
- **Then** the request is rejected with a business state conflict.
- **And** the error explains that delivered orders cannot be cancelled and advises the agent on the return/refund policy.

### Scenario 7: AI Agent MCP Tool Invocation for Order Placement & Listing
- **Given** an authenticated MCP client session.
- **When** the AI agent calls MCP tool `place_order` with valid arguments.
- **Then** the tool returns a formatted confirmation including the generated order number, items, and total amount.
- **When** the AI agent calls `list_customer_orders` with a customer ID.
- **Then** the tool returns a formatted list of the customer's orders.

---

## 5. Out of Scope

1. **Payment Gateway Integration:** Direct credit card processing, payment gateways (Stripe/PayPal), and 3D-Secure webhooks are out of scope for this MVP (orders default to direct store authorization).
2. **Shopping Cart Sessions / Abandoned Cart Reminders:** Dedicated temporary shopping cart storage and session persistence are deferred to a separate Cart PRD candidate.
3. **Complex Promotion Voucher Codes:** Discount codes, multi-buy promotions, and loyalty points calculations are out of scope (deferred to PRD-006 candidate).
4. **Physical Shipment Tracking Carrier APIs:** Integration with third-party logistics carriers (FedEx, DHL) is out of scope; internal status progression suffices for this milestone.

> **Note (2026-09-30, PRD-007):** `CONFIRMED` now means "claimed by a partner": a fulfilment partner claims a `PLACED` order first-wins, and the order records that partner as `assignedPartner`. Staff no longer confirm orders. See [EM-002](../../technical/event-models/EM-002-order-lifecycle-notifications-and-fulfilment.md).
