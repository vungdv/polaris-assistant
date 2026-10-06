package vn.danang.polaris.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.events.order.OrderMilestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Event contracts (EM-002 §4 + Δ4) JSON round-trip")
class EventContractsJsonTest {

    /** Strict on unknown properties, so the contracts' own annotations are what make them tolerant. */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-28T09:15:02.311Z");

    private static OrderLifecycleEvent confirmedOrder(String assignedPartner) {
        return new OrderLifecycleEvent(
                "ORD-10042",
                OrderMilestone.CONFIRMED,
                OCCURRED_AT,
                new OrderLifecycleEvent.Customer(1L, "Alice Tran", "alice.tran@example.com"),
                List.of(
                        new OrderLifecycleEvent.Item("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                        new OrderLifecycleEvent.Item("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                new BigDecimal("99.70"),
                "USD",
                assignedPartner);
    }

    @Nested
    @DisplayName("OrderLifecycleEvent")
    class OrderLifecycle {

        @Test
        @DisplayName("round-trips unchanged, with assignedPartner")
        void roundTrip() {
            OrderLifecycleEvent event = confirmedOrder("partner-north");

            OrderLifecycleEvent read = mapper.readValue(mapper.writeValueAsString(event), OrderLifecycleEvent.class);

            assertThat(read).isEqualTo(event);
        }

        @Test
        @DisplayName("money is written as a JSON string and keeps its scale")
        void moneyIsString() {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(confirmedOrder(null)));

            assertThat(json.get("totalAmount").isString()).isTrue();
            assertThat(json.get("totalAmount").asString()).isEqualTo("99.70");
            assertThat(json.get("items").get(0).get("unitPrice").isString()).isTrue();
            assertThat(json.get("items").get(0).get("unitPrice").asString()).isEqualTo("49.90");
            assertThat(json.get("occurredAt").asString()).isEqualTo("2026-09-28T09:15:02.311Z");
            assertThat(json.get("status").asString()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("reads the EM-002 §4.2 example: missing assignedPartner is null")
        void readsEm002Example() {
            String json = """
                    {
                      "orderNumber": "ORD-10042",
                      "status": "CONFIRMED",
                      "occurredAt": "2026-09-28T09:15:02.311Z",
                      "customer": { "id": 1, "name": "Alice Tran", "email": "alice.tran@example.com" },
                      "items": [
                        { "sku": "NG-EARBUD-01", "name": "Nova Wireless Earbuds", "quantity": 1, "unitPrice": "49.90" },
                        { "sku": "NG-CHARGER-01", "name": "Fast Charger", "quantity": 2, "unitPrice": "24.90" }
                      ],
                      "totalAmount": "99.70",
                      "currency": "USD"
                    }
                    """;

            OrderLifecycleEvent read = mapper.readValue(json, OrderLifecycleEvent.class);

            assertThat(read).isEqualTo(confirmedOrder(null));
            assertThat(read.totalAmount()).isEqualByComparingTo("99.70");
            assertThat(read.totalAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts an explicit null assignedPartner and writes it back as null")
        void explicitNullAssignedPartner() {
            OrderLifecycleEvent event = confirmedOrder(null);
            String json = mapper.writeValueAsString(event);

            assertThat(mapper.readTree(json).get("assignedPartner").isNull()).isTrue();
            assertThat(mapper.readValue(json, OrderLifecycleEvent.class).assignedPartner()).isNull();
        }

        @Test
        @DisplayName("ignores unknown fields at every level (additive-only evolution)")
        void ignoresUnknownFields() {
            String json = """
                    {
                      "orderNumber": "ORD-10042",
                      "status": "PLACED",
                      "occurredAt": "2026-09-28T09:15:02.311Z",
                      "customer": { "id": 1, "name": "Alice Tran", "email": "alice.tran@example.com", "tier": "gold" },
                      "items": [ { "sku": "S", "name": "N", "quantity": 1, "unitPrice": "1.00", "discount": "0.10" } ],
                      "totalAmount": "1.00",
                      "currency": "USD",
                      "assignedPartner": null,
                      "futureField": { "nested": true }
                    }
                    """;

            OrderLifecycleEvent read = mapper.readValue(json, OrderLifecycleEvent.class);

            assertThat(read.status()).isEqualTo(OrderMilestone.PLACED);
            assertThat(read.customer().email()).isEqualTo("alice.tran@example.com");
            assertThat(read.items()).singleElement().extracting(OrderLifecycleEvent.Item::unitPrice)
                    .isEqualTo(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("rejects a payload without the order number")
        void requiresOrderNumber() {
            String json = """
                    { "status": "PLACED", "occurredAt": "2026-09-28T09:15:02.311Z" }
                    """;

            assertThatThrownBy(() -> mapper.readValue(json, OrderLifecycleEvent.class))
                    .hasRootCauseInstanceOf(NullPointerException.class)
                    .hasStackTraceContaining("orderNumber");
        }
    }

    @Nested
    @DisplayName("ShipmentEvent")
    class Shipment {

        @Test
        @DisplayName("round-trips unchanged, with partnerId")
        void roundTrip() {
            ShipmentEvent event = new ShipmentEvent("ORD-10042", "SHP-7f3c", "partner-south", ShipmentStep.PACKED,
                    Instant.parse("2026-09-28T09:15:07.402Z"));

            String json = mapper.writeValueAsString(event);

            assertThat(mapper.readTree(json).get("partnerId").asString()).isEqualTo("partner-south");
            assertThat(mapper.readValue(json, ShipmentEvent.class)).isEqualTo(event);
        }

        @Test
        @DisplayName("ignores unknown fields")
        void ignoresUnknownFields() {
            String json = """
                    { "orderNumber": "ORD-1", "shipmentId": "SHP-1", "partnerId": "partner-central",
                      "step": "DISPATCHED", "occurredAt": "2026-09-28T09:15:07.402Z", "carrier": "x" }
                    """;

            assertThat(mapper.readValue(json, ShipmentEvent.class).step()).isEqualTo(ShipmentStep.DISPATCHED);
        }

        @Test
        @DisplayName("rejects a payload without the required partnerId (Δ4)")
        void requiresPartnerId() {
            String json = """
                    { "orderNumber": "ORD-1", "shipmentId": "SHP-1", "step": "PACKED",
                      "occurredAt": "2026-09-28T09:15:07.402Z" }
                    """;

            assertThatThrownBy(() -> mapper.readValue(json, ShipmentEvent.class))
                    .hasRootCauseInstanceOf(NullPointerException.class)
                    .hasStackTraceContaining("partnerId");
        }
    }

    @Nested
    @DisplayName("Type names and destinations")
    class Names {

        @Test
        @DisplayName("match the EM-002 §4 catalogue")
        void catalogue() {
            assertThat(OrderEvents.DESTINATION).isEqualTo("polaris.order.lifecycle");
            assertThat(OrderEvents.SOURCE).isEqualTo("/polaris/order");
            assertThat(FulfilmentEvents.DESTINATION).isEqualTo("polaris.fulfilment.shipments");
            assertThat(FulfilmentEvents.SOURCE).isEqualTo("/polaris/fulfilment");
            assertThat(OrderMilestone.values()).extracting(OrderMilestone::type).containsExactly(
                    "vn.danang.polaris.order.placed.v1",
                    "vn.danang.polaris.order.confirmed.v1",
                    "vn.danang.polaris.order.parceled.v1",
                    "vn.danang.polaris.order.delivering.v1",
                    "vn.danang.polaris.order.delivered.v1");
            assertThat(ShipmentStep.values()).extracting(ShipmentStep::type).containsExactly(
                    "vn.danang.polaris.fulfilment.shipment.packed.v1",
                    "vn.danang.polaris.fulfilment.shipment.dispatched.v1",
                    "vn.danang.polaris.fulfilment.shipment.delivered.v1");
        }

        @ParameterizedTest
        @EnumSource(OrderMilestone.class)
        @DisplayName("order milestone resolves from its type")
        void milestoneFromType(OrderMilestone milestone) {
            assertThat(OrderMilestone.fromType(milestone.type())).contains(milestone);
        }

        @ParameterizedTest
        @EnumSource(ShipmentStep.class)
        @DisplayName("shipment step resolves from its type")
        void stepFromType(ShipmentStep step) {
            assertThat(ShipmentStep.fromType(step.type())).contains(step);
        }

        @Test
        @DisplayName("unknown types resolve to empty")
        void unknownType() {
            assertThat(OrderMilestone.fromType("vn.danang.polaris.order.cancelled.v1")).isEmpty();
            assertThat(OrderMilestone.fromType(ShipmentStep.PACKED.type())).isEmpty();
            assertThat(ShipmentStep.fromType(OrderMilestone.DELIVERED.type())).isEmpty();
            assertThat(ShipmentStep.fromType(null)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "org.springframework.core.SpringVersion",
            "jakarta.persistence.Entity",
            "org.hibernate.Session",
            "org.flywaydb.core.Flyway"})
    @DisplayName("library classpath has no Spring or persistence dependencies (Δ5)")
    void noSpringOrPersistence(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }
}
