package vn.danang.polaris.fulfilment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.order.OrderEvents;

/**
 * F2 acceptance against a real Kafka broker (same image, no topic auto-creation) with Order's claim endpoint and the
 * Keycloak token endpoint stubbed by WireMock (the external boundaries). Two partner outcomes are driven by the
 * claim stub: {@value #WINNER} gets 200, the others 409.
 */
@SpringBootTest(properties = {
        "polaris.fulfilment.claim-pause.min=100ms",
        "polaris.fulfilment.claim-pause.max=300ms",
        "polaris.fulfilment.step-delay=100ms",
        "polaris.fulfilment.random-seed=11",
        "polaris.fulfilment.auth.client-secret=test-secret",
        "polaris.fulfilment.topics.shipment-replicas=1",
        "polaris.fulfilment.topics.shipment-min-insync-replicas=1",
        "management.otlp.metrics.export.enabled=false",
        "management.opentelemetry.tracing.export.otlp.timeout=1s"
})
@Import(FulfilmentEmulatorIntegrationTest.Infrastructure.class)
class FulfilmentEmulatorIntegrationTest {

    private static final String WINNER = "partner-central";
    private static final List<String> PARTNERS = List.of("partner-north", "partner-central", "partner-south");
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    private static final WireMockServer ORDER_API = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka:3.9.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        }

        /** Order owns this topic in the real system; the test plays Order. */
        @Bean
        NewTopic orderLifecycleTopic() {
            return TopicBuilder.name(OrderEvents.DESTINATION).partitions(1).replicas(1).build();
        }

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @BeforeAll
    static void startOrderApi() {
        ORDER_API.start();
    }

    @AfterAll
    static void stopOrderApi() {
        ORDER_API.stop();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("polaris.fulfilment.order-api.base-url", ORDER_API::baseUrl);
        registry.add("polaris.fulfilment.auth.token-uri", () -> ORDER_API.baseUrl() + "/token");
    }

    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private InMemorySpanExporter spans;

    private KafkaConsumer<String, byte[]> shipmentsConsumer;

    @BeforeEach
    void stubs() {
        ORDER_API.resetAll();
        ORDER_API.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        shipmentsConsumer = consumer();
        // Fulfilment provisions its topic at startup; subscribe from the beginning so nothing is missed.
        shipmentsConsumer.subscribe(List.of(FulfilmentEvents.DESTINATION));
    }

    @AfterEach
    void closeConsumer() {
        shipmentsConsumer.close();
    }

    private void stubClaims(String orderNumber, int loserStatus) {
        ORDER_API.stubFor(post(urlPathEqualTo("/api/v1/orders/" + orderNumber + "/claim"))
                .withRequestBody(equalToJson("{\"partnerId\":\"" + WINNER + "\"}"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"orderNumber\":\"" + orderNumber + "\",\"status\":\"CONFIRMED\",\"assignedPartner\":\"" + WINNER + "\"}")));
        ORDER_API.stubFor(post(urlPathEqualTo("/api/v1/orders/" + orderNumber + "/claim"))
                .withRequestBody(matching(".*\"partnerId\":\"(?!" + WINNER + ").*"))
                .willReturn(aResponse().withStatus(loserStatus).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":" + loserStatus + ",\"orderStatus\":\"CONFIRMED\"}")));
    }

    @Test
    @DisplayName("starts without a datasource: no DataSource bean, actuator only")
    void startsWithoutDatasource() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(OfferListenerRegistrar.class).values()).allMatch(OfferListenerRegistrar::isRunning);
    }

    @Test
    @DisplayName("one offer: a claim per partner with a service token; winner reports 3 steps in one trace; losers report none")
    void offerToShipments() throws Exception {
        String order = "ORD-OFFER";
        stubClaims(order, 409);
        publishOffer(order);

        List<ConsumerRecord<String, byte[]>> shipments = pollShipments(order, 3);

        // One claim per partner, distinct partnerIds, each with the service-account token.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> ORDER_API.verify(3, postRequestedFor(urlPathEqualTo("/api/v1/orders/" + order + "/claim"))));
        for (String partner : PARTNERS) {
            ORDER_API.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/orders/" + order + "/claim"))
                    .withHeader("Authorization", containing("Bearer svc-token"))
                    .withRequestBody(equalToJson("{\"partnerId\":\"" + partner + "\"}")));
        }
        // Token: client_credentials, client secret from the environment-backed property; fetched once and cached.
        ORDER_API.verify(1, postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withHeader("Authorization", containing("Basic ")));

        // Winner: exactly three events, in order, with its partnerId; nothing from the losers.
        assertThat(shipments).extracting(r -> r.key()).containsOnly(order);
        assertThat(shipments).extracting(r -> ceType(r)).containsExactly(
                FulfilmentEvents.SHIPMENT_PACKED_V1, FulfilmentEvents.SHIPMENT_DISPATCHED_V1, FulfilmentEvents.SHIPMENT_DELIVERED_V1);
        assertThat(shipments).allSatisfy(r -> assertThat(new String(r.value(), StandardCharsets.UTF_8))
                .contains("\"partnerId\":\"" + WINNER + "\"").contains("\"orderNumber\":\"" + order + "\""));
        assertThat(pollMore(order, Duration.ofSeconds(2))).isEmpty();

        // One trace: the offer's trace id is on every claim request and every shipment record.
        ORDER_API.verify(3, postRequestedFor(urlPathEqualTo("/api/v1/orders/" + order + "/claim"))
                .withHeader("traceparent", matching("00-" + TRACE_ID + "-[0-9a-f]{16}-0[01]")));
        assertThat(shipments).allSatisfy(r -> assertThat(header(r, "traceparent")).startsWith("00-" + TRACE_ID + "-"));
        // The batch span processor exports asynchronously; spans of the trace cover the offer (consume) and the sends.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(TRACE_ID)).map(SpanData::getName))
                        .anyMatch(n -> n.contains(OrderEvents.DESTINATION))
                        .anyMatch(n -> n.contains(FulfilmentEvents.DESTINATION)));
    }

    @Test
    @DisplayName("a failed claim is not retried and reports nothing")
    void failedClaimIsNotRetried() throws Exception {
        String order = "ORD-FAIL";
        ORDER_API.stubFor(post(urlPathEqualTo("/api/v1/orders/" + order + "/claim")).willReturn(aResponse().withStatus(500)));
        publishOffer(order);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> ORDER_API.verify(3, postRequestedFor(urlPathEqualTo("/api/v1/orders/" + order + "/claim"))));
        assertThat(pollMore(order, Duration.ofSeconds(2))).isEmpty();
        ORDER_API.verify(3, postRequestedFor(urlPathEqualTo("/api/v1/orders/" + order + "/claim")));
    }

    // -- helpers -----------------------------------------------------------------------------------------------

    private void publishOffer(String orderNumber) throws Exception {
        String payload = "{\"orderNumber\":\"" + orderNumber + "\",\"status\":\"PLACED\",\"occurredAt\":\"2026-09-30T10:00:00Z\","
                + "\"items\":[],\"totalAmount\":\"9.90\",\"currency\":\"USD\"}";
        CloudEvent event = CloudEventBuilder.v1().withId(UUID.randomUUID().toString()).withType(OrderEvents.PLACED_V1)
                .withSource(java.net.URI.create(OrderEvents.SOURCE)).withTime(Instant.now().atOffset(ZoneOffset.UTC))
                .withDataContentType("application/json").withData(payload.getBytes(StandardCharsets.UTF_8)).build();
        ProducerRecord<String, byte[]> record = KafkaMessageFactory.createWriter(OrderEvents.DESTINATION, orderNumber).writeBinary(event);
        record.headers().add("traceparent", TRACEPARENT.getBytes(StandardCharsets.UTF_8));
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            producer.send(record).get();
        }
    }

    private KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private final List<ConsumerRecord<String, byte[]>> polled = new ArrayList<>();

    /** Shipment records of one order (the topic is shared by the tests); the consumer reads from the beginning. */
    private List<ConsumerRecord<String, byte[]>> pollShipments(String order, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            shipmentsConsumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (order.equals(r.key())) {
                    polled.add(r);
                }
            });
            return polled.size() >= expected;
        });
        return List.copyOf(polled);
    }

    private List<ConsumerRecord<String, byte[]>> pollMore(String order, Duration duration) {
        List<ConsumerRecord<String, byte[]>> more = new ArrayList<>();
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            shipmentsConsumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (order.equals(r.key())) {
                    more.add(r);
                }
            });
        }
        return more;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String ceType(ConsumerRecord<String, byte[]> record) {
        return header(record, "ce_type");
    }
}
