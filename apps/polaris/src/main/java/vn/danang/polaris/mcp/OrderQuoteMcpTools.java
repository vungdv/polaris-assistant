package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;
import vn.danang.polaris.order.service.OrderQuoteService;

/**
 * MCP facade for the read-only {@code quote_order} tool (BPMN {@code O_Verify}).
 * <p>
 * Successful results carry {@code structuredContent} shaped exactly like {@link QuoteResponse} plus a
 * short text summary for the model. Lines that cannot be ordered are reported per line, not as a tool error.
 */
@Component
public class OrderQuoteMcpTools {

    public static final String TOOL_QUOTE_ORDER = "quote_order";

    private static final String QUOTE_ORDER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "items": {
              "type": "array",
              "description": "Line items to check against live stock and price (lines repeating a SKU are merged)",
              "minItems": 1,
              "maxItems": 50,
              "items": {
                "type": "object",
                "properties": {
                  "sku": {
                    "type": "string",
                    "description": "Product SKU code (e.g. 'NG-CHARGER-01')"
                  },
                  "quantity": {
                    "type": "integer",
                    "minimum": 1,
                    "description": "Requested quantity (whole number, minimum 1)"
                  }
                },
                "required": ["sku", "quantity"]
              }
            }
          },
          "required": ["items"]
        }
        """;

    // Mirrors QuoteResponse: the published structuredContent contract consumed by the AI Assistant.
    private static final String QUOTE_ORDER_OUTPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "orderable": {
              "type": "boolean",
              "description": "True when every line can be ordered as requested right now"
            },
            "lines": {
              "type": "array",
              "description": "One entry per distinct requested SKU, in order of first appearance",
              "items": {
                "type": "object",
                "properties": {
                  "sku": { "type": "string" },
                  "name": { "type": ["string", "null"] },
                  "requestedQuantity": { "type": "integer" },
                  "unitPrice": { "type": ["number", "null"] },
                  "availableQuantity": { "type": ["integer", "null"] },
                  "lineTotal": { "type": ["number", "null"] },
                  "problem": {
                    "type": ["string", "null"],
                    "enum": ["not_found", "inactive", "insufficient_stock", null]
                  }
                },
                "required": ["sku", "name", "requestedQuantity", "unitPrice", "availableQuantity", "lineTotal", "problem"]
              }
            },
            "totalAmount": {
              "type": "number",
              "description": "Sum of the line totals of all orderable lines at live prices"
            }
          },
          "required": ["orderable", "lines", "totalAmount"]
        }
        """;

    private final OrderQuoteService orderQuoteService;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public OrderQuoteMcpTools(OrderQuoteService orderQuoteService, ObjectProvider<Tracer> tracerProvider) {
        this.orderQuoteService = orderQuoteService;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public OrderQuoteMcpTools(OrderQuoteService orderQuoteService) {
        this(orderQuoteService, null);
    }

    public McpSchema.Tool getQuoteOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_QUOTE_ORDER, jsonMapper, QUOTE_ORDER_SCHEMA)
                .outputSchema(jsonMapper, QUOTE_ORDER_OUTPUT_SCHEMA)
                .description("Read-only check of live price and stock for prospective order lines. Returns per-line unit price, "
                        + "available stock, line total and problem (not_found, inactive, insufficient_stock), plus the total. "
                        + "Does not place, reserve or change anything.")
                .build();
    }

    public McpSchema.Tool getQuoteOrderTool() {
        return getQuoteOrderTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    private McpSchema.CallToolResult executeWithSpan(String toolName, Supplier<McpSchema.CallToolResult> execution) {
        if (this.tracer == null) {
            return execution.get();
        }

        String spanName = "mcp.server.tool_call %s".formatted(toolName);
        Span span = this.tracer.nextSpan().name(spanName);
        span.tag("gen_ai.tool.name", toolName);
        span.tag("mcp.server", "polaris-mcp");
        span.tag("mcp.category", "order");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            McpSchema.CallToolResult result = execution.get();
            if (result != null && Boolean.TRUE.equals(result.isError())) {
                span.tag("error", "true");
            }
            return result;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    public McpSchema.CallToolResult quoteOrder(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_QUOTE_ORDER, () -> {
            Object rawItems = arguments != null ? arguments.get("items") : null;
            if (!(rawItems instanceof List<?> itemsList) || itemsList.isEmpty()) {
                return error("Parameter 'items' is required and must not be empty.");
            }
            if (itemsList.size() > QuoteRequest.MAX_ITEMS) {
                return error("Parameter 'items' must contain at most " + QuoteRequest.MAX_ITEMS + " items.");
            }

            List<QuoteRequest.Item> items = new ArrayList<>();
            for (Object itemObj : itemsList) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    return error("Each item must be an object with 'sku' and 'quantity'.");
                }
                Object rawSku = itemMap.get("sku");
                if (rawSku == null || rawSku.toString().isBlank()) {
                    return error("Item 'sku' is required.");
                }
                String sku = rawSku.toString().trim();
                Integer qty = parseInteger(itemMap.get("quantity"));
                if (qty == null || qty < 1) {
                    return error("Item 'quantity' must be a whole number of at least 1 for SKU '" + sku + "'.");
                }
                items.add(new QuoteRequest.Item(sku, qty));
            }

            try {
                QuoteResponse quote = orderQuoteService.quote(items);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(formatQuote(quote, items.size()))
                        .structuredContent(quote)
                        .isError(false)
                        .build();
            } catch (Exception ex) {
                return error("Error quoting order: " + ex.getMessage());
            }
        });
    }

    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private String formatQuote(QuoteResponse quote, int requestedLines) {
        List<String> lines = new ArrayList<>();
        lines.add(quote.orderable()
                ? "Quote: all lines can be ordered at live prices (nothing reserved)."
                : "Quote: some lines cannot be ordered as requested (nothing reserved).");
        if (quote.lines().size() < requestedLines) {
            lines.add("- Repeated SKUs were merged into one line with the summed quantity.");
        }
        for (QuoteResponse.Line line : quote.lines()) {
            if (line.problem() == null) {
                lines.add(String.format("  * [%s] %s x %d @ $%s = $%s (in stock: %d)",
                        line.sku(), line.name(), line.requestedQuantity(), line.unitPrice(),
                        line.lineTotal(), line.availableQuantity()));
            } else if (line.problem() == QuoteResponse.Problem.NOT_FOUND) {
                lines.add(String.format("  * [%s] not_found: no product with this SKU", line.sku()));
            } else if (line.problem() == QuoteResponse.Problem.INACTIVE) {
                lines.add(String.format("  * [%s] %s inactive: product is not currently sold", line.sku(), line.name()));
            } else {
                lines.add(String.format("  * [%s] %s insufficient_stock: requested %d, available %d @ $%s",
                        line.sku(), line.name(), line.requestedQuantity(), line.availableQuantity(), line.unitPrice()));
            }
        }
        lines.add("- Total (orderable lines): $" + quote.totalAmount());
        return String.join("\n", lines);
    }

    /** Parses a whole number; fractional (e.g. 2.7) or out-of-range values yield null rather than being truncated. */
    private Integer parseInteger(Object val) {
        if (val == null) return null;
        try {
            BigDecimal number = val instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(val.toString().trim());
            return number.stripTrailingZeros().scale() <= 0 ? number.intValueExact() : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
