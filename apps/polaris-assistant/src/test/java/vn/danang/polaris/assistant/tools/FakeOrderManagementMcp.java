package vn.danang.polaris.assistant.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Test double of Order Management's {@code place_order} MCP tool at the {@link PolarisMcpClient} boundary. It
 * reproduces the published contract (OrderMcpTools): one order per idempotency key, a replay text for a
 * repeated key, and RFC 7807 problems in {@code structuredContent} for failures.
 */
public class FakeOrderManagementMcp {

    public static final String PLACE_ORDER = "place_order";

    private final Map<String, String> orderByKey = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1000);
    private final AtomicInteger ordersCreated = new AtomicInteger();
    private final List<Map<String, Object>> placeOrderCalls = Collections.synchronizedList(new ArrayList<>());
    private volatile CallToolResult rejection;
    private volatile CyclicBarrier barrier;
    private volatile Runnable duringPlacement;

    /** Every {@code place_order} call from now on answers with this result instead of placing an order. */
    public void rejectWith(CallToolResult result) {
        this.rejection = result;
    }

    public void acceptOrders() {
        this.rejection = null;
    }

    /** The next {@code parties} calls wait for each other, so they are all in flight at the same time. */
    public void holdConcurrentCalls(int parties) {
        this.barrier = new CyclicBarrier(parties);
    }

    /** Runs once inside the next {@code place_order} call, e.g. to race it with a cancel. */
    public void duringNextPlacement(Runnable action) {
        this.duringPlacement = action;
    }

    public int ordersCreated() {
        return ordersCreated.get();
    }

    public List<Map<String, Object>> placeOrderCalls() {
        synchronized (placeOrderCalls) {
            return List.copyOf(placeOrderCalls);
        }
    }

    public String orderNumberFor(String idempotencyKey) {
        return orderByKey.get(idempotencyKey);
    }

    public CallToolResult placeOrder(Map<String, Object> arguments) {
        placeOrderCalls.add(new LinkedHashMap<>(arguments));
        CyclicBarrier held = barrier;
        if (held != null) {
            try {
                held.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Concurrent place_order calls did not meet", e);
            }
        }
        Runnable action = duringPlacement;
        if (action != null) {
            duringPlacement = null;
            action.run();
        }
        CallToolResult rejected = rejection;
        if (rejected != null) {
            return rejected;
        }
        String key = String.valueOf(arguments.get("idempotency_key"));
        AtomicBoolean created = new AtomicBoolean();
        String orderNumber = orderByKey.computeIfAbsent(key, k -> {
            created.set(true);
            ordersCreated.incrementAndGet();
            return "ORD-%06d".formatted(sequence.incrementAndGet());
        });
        String text = (created.get()
                ? "Order successfully placed!"
                : "Order already placed for this idempotency key; no new order was created.")
                + "\n- Order Number: " + orderNumber + "\n- Status: PLACED\n- Customer: Alice Tran\n- Total Amount: $49.80";
        return new CallToolResult(List.of(TextContent.builder(text).build()), false, null, Map.of());
    }

    /** A failed {@code place_order} result shaped like OrderMcpTools#problemResult. */
    public static CallToolResult problem(String type, int status, String title, String detail, Map<String, Object> extensions) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://polaris.local/errors/" + type);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.putAll(extensions);
        return new CallToolResult(List.of(TextContent.builder(detail).build()), true, problem, Map.of());
    }

    /** A {@link PolarisMcpClient} backed by this fake (only {@code place_order}). */
    public PolarisMcpClient asClient() {
        return new PolarisMcpClient() {
            @Override
            public List<Tool> listAvailableTools() {
                return List.of(Tool.builder(PLACE_ORDER, Map.of()).build());
            }

            @Override
            public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
                if (!PLACE_ORDER.equals(toolName)) {
                    throw new IllegalArgumentException("Unexpected tool " + toolName);
                }
                return placeOrder(arguments);
            }
        };
    }
}
