package vn.danang.polaris.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/** Partner behaviour with a deterministic scheduler (seeded pauses, no real time) and a stubbed claim endpoint. */
class PartnerAgentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
    private static final List<String> PARTNERS = List.of("partner-north", "partner-central", "partner-south");

    /** Records every scheduled task with its delay; {@link #runAll()} executes them in due-time order. */
    private static final class ManualScheduler {
        record Scheduled(long dueMs, Runnable task) {
        }

        final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        final List<Scheduled> queue = new ArrayList<>();
        long now;

        ManualScheduler() {
            when(executor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
                queue.add(new Scheduled(now + call.<Long>getArgument(1), call.getArgument(0)));
                return null;
            });
        }

        void runAll() {
            while (!queue.isEmpty()) {
                Scheduled next = queue.stream().min(Comparator.comparingLong(Scheduled::dueMs)).orElseThrow();
                queue.remove(next);
                now = next.dueMs();
                next.task().run();
            }
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private final ShipmentPublisher shipments = mock(ShipmentPublisher.class);
    private final FulfilmentMetrics metrics = new FulfilmentMetrics(new SimpleMeterRegistry());
    private FulfilmentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FulfilmentProperties(PARTNERS,
                new FulfilmentProperties.ClaimPause(Duration.ofSeconds(1), Duration.ofSeconds(5)), Duration.ofSeconds(2),
                42L, new FulfilmentProperties.Topics("o", "s", 1, (short) 1, 1),
                new FulfilmentProperties.OrderApi("http://unused"), new FulfilmentProperties.Auth("http://unused", "c", "s"));
    }

    private List<PartnerAgent> agents(ClaimClient claimClient, Random random) {
        return PARTNERS.stream()
                .map(id -> new PartnerAgent(id, properties, random, scheduler.executor, claimClient, shipments, metrics, CLOCK))
                .toList();
    }

    @Test
    @DisplayName("one offer -> one claim per partner, each with a distinct partnerId")
    void oneClaimPerPartner() {
        ClaimClient claimClient = mock(ClaimClient.class);
        when(claimClient.claim(any(), any())).thenReturn(ClaimResult.LOST);

        agents(claimClient, new Random(1)).forEach(a -> a.onOffer("ORD-1"));
        scheduler.runAll();

        ArgumentCaptor<String> partnerIds = ArgumentCaptor.forClass(String.class);
        verify(claimClient, Mockito.times(3)).claim(eq("ORD-1"), partnerIds.capture());
        assertThat(partnerIds.getAllValues()).containsExactlyInAnyOrderElementsOf(PARTNERS);
    }

    @Test
    @DisplayName("winner (200) emits packed, dispatched, delivered in order with its partnerId, a step delay apart")
    void winnerReportsThreeSteps() {
        ClaimClient claimClient = mock(ClaimClient.class);
        when(claimClient.claim("ORD-1", "partner-north")).thenReturn(ClaimResult.WON);

        agents(claimClient, new Random(1)).get(0).onOffer("ORD-1");
        scheduler.runAll();

        InOrder order = Mockito.inOrder(shipments);
        order.verify(shipments).publish("ORD-1", "partner-north", ShipmentStep.PACKED, CLOCK.instant());
        order.verify(shipments).publish("ORD-1", "partner-north", ShipmentStep.DISPATCHED, CLOCK.instant());
        order.verify(shipments).publish("ORD-1", "partner-north", ShipmentStep.DELIVERED, CLOCK.instant());
        Mockito.verifyNoMoreInteractions(shipments);
        // Claim pause in [1s, 5s], then PACKED at once, then 2s per step.
        assertThat(scheduler.now).isBetween(1_000L + 4_000L, 5_000L + 4_000L);
    }

    @Test
    @DisplayName("loser (409) and failed claims emit nothing and are never retried")
    void loserAndFailedDropTheOffer() {
        ClaimClient claimClient = mock(ClaimClient.class);
        when(claimClient.claim(any(), eq("partner-north"))).thenReturn(ClaimResult.LOST);
        when(claimClient.claim(any(), eq("partner-central"))).thenReturn(ClaimResult.FAILED);

        List<PartnerAgent> agents = agents(claimClient, new Random(1));
        agents.get(0).onOffer("ORD-1");
        agents.get(1).onOffer("ORD-1");
        scheduler.runAll();

        verify(claimClient, Mockito.times(1)).claim("ORD-1", "partner-north");
        verify(claimClient, Mockito.times(1)).claim("ORD-1", "partner-central");
        verify(shipments, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("seeded random pauses over 10 offers produce more than one distinct winner")
    void seededPausesGiveSeveralWinners() {
        // The first claim for an order wins (200); later ones get 409, as Order's first-wins claim does.
        Set<String> claimed = new HashSet<>();
        Set<String> winners = new HashSet<>();
        ClaimClient firstWins = (orderNumber, partnerId) -> {
            if (claimed.add(orderNumber)) {
                winners.add(partnerId);
                return ClaimResult.WON;
            }
            return ClaimResult.LOST;
        };

        List<PartnerAgent> agents = agents(firstWins, new Random(7));
        for (int i = 1; i <= 10; i++) {
            String orderNumber = "ORD-" + i;
            agents.forEach(a -> a.onOffer(orderNumber));
        }
        scheduler.runAll();

        assertThat(claimed).hasSize(10);
        assertThat(winners).hasSizeGreaterThan(1);
    }
}
