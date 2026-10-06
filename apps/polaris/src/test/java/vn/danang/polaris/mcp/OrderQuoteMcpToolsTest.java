package vn.danang.polaris.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;

@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class OrderQuoteMcpToolsTest {

    @Autowired
    private OrderQuoteMcpTools orderQuoteMcpTools;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private HttpServletStatelessServerTransport statelessTransport;

    @Test
    @DisplayName("quote_order tool schema contract")
    void quoteOrder_schemaContract() {
        McpSchema.Tool tool = orderQuoteMcpTools.getQuoteOrderTool();
        assertThat(tool.name()).isEqualTo("quote_order");
        assertThat(tool.description()).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        assertThat(properties).containsKey("items");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.inputSchema().get("required");
        assertThat(required).containsExactly("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemsSchema = (Map<String, Object>) properties.get("items");
        assertThat(itemsSchema).containsEntry("maxItems", QuoteRequest.MAX_ITEMS);

        assertThat(tool.outputSchema()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProps = (Map<String, Object>) tool.outputSchema().get("properties");
        assertThat(outputProps).containsOnlyKeys("orderable", "lines", "totalAmount");
    }

    @Test
    @DisplayName("quote_order returns structuredContent matching QuoteResponse plus a text summary")
    void quoteOrder_success_structuredContent() {
        McpSchema.CallToolResult result = orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(
                Map.of("sku", "NG-CHARGER-01", "quantity", 2),
                Map.of("sku", "NOPE-404", "quantity", 1))));

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(QuoteResponse.class);
        QuoteResponse quote = (QuoteResponse) result.structuredContent();
        assertThat(quote.orderable()).isFalse();
        assertThat(quote.totalAmount()).isEqualByComparingTo("49.80");
        assertThat(quote.lines().get(0).problem()).isNull();
        assertThat(quote.lines().get(1).problem()).isEqualTo(QuoteResponse.Problem.NOT_FOUND);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("NG-CHARGER-01").contains("$49.80").contains("[NOPE-404] not_found");
    }

    @Test
    @DisplayName("quote_order does not change stock")
    void quoteOrder_isReadOnly() {
        int stock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 3))));
        assertThat(productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty()).isEqualTo(stock);
    }

    @Test
    @DisplayName("quote_order rejects missing or malformed items as a tool error")
    void quoteOrder_invalidArgs() {
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of()).isError()).isTrue();
        assertThat(orderQuoteMcpTools.quoteOrder(null).isError()).isTrue();
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of("items", List.of())).isError()).isTrue();
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(Map.of("sku", "NG-CASE-01", "quantity", 0))))
                .isError()).isTrue();
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(Map.of("quantity", 1)))).isError()).isTrue();
        List<Map<String, Object>> tooMany = java.util.stream.IntStream.rangeClosed(0, QuoteRequest.MAX_ITEMS)
                .mapToObj(i -> Map.<String, Object>of("sku", "NG-CASE-01", "quantity", 1)).toList();
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of("items", tooMany)).isError()).isTrue();
    }

    @Test
    @DisplayName("quote_order rejects fractional quantities instead of truncating them")
    void quoteOrder_fractionalQuantity_rejected() {
        McpSchema.CallToolResult result = orderQuoteMcpTools.quoteOrder(
                Map.of("items", List.of(Map.of("sku", "NG-CASE-01", "quantity", 2.7))));
        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("whole number");
        assertThat(orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(Map.of("sku", "NG-CASE-01", "quantity", "2.7"))))
                .isError()).isTrue();

        McpSchema.CallToolResult whole = orderQuoteMcpTools.quoteOrder(
                Map.of("items", List.of(Map.of("sku", "NG-CASE-01", "quantity", 2.0))));
        assertThat(whole.isError()).isFalse();
        assertThat(((QuoteResponse) whole.structuredContent()).lines().get(0).requestedQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("quote_order merges repeated SKUs and says so in the text summary")
    void quoteOrder_repeatedSku_merged() {
        McpSchema.CallToolResult result = orderQuoteMcpTools.quoteOrder(Map.of("items", List.of(
                Map.of("sku", "NG-CHARGER-01", "quantity", 1),
                Map.of("sku", "ng-charger-01", "quantity", 2))));

        QuoteResponse quote = (QuoteResponse) result.structuredContent();
        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().get(0).requestedQuantity()).isEqualTo(3);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("merged");
    }

    @Test
    @DisplayName("quote_order over MCP wire (stateless /mcp): structuredContent serializes with QuoteResponse field names")
    void quoteOrder_overWire_structuredContentJson() throws Exception {
        String body = callStateless("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"quote_order",
                  "arguments":{"items":[{"sku":"NG-CHARGER-01","quantity":2},{"sku":"NOPE-404","quantity":1}]}
                }}
                """);

        JsonNode result = new ObjectMapper().readTree(body).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("orderable").asBoolean()).isFalse();
        assertThat(structured.path("totalAmount").decimalValue()).isEqualByComparingTo("49.80");
        JsonNode first = structured.path("lines").get(0);
        assertThat(first.path("sku").asText()).isEqualTo("NG-CHARGER-01");
        assertThat(first.path("name").asText()).isEqualTo("Nova 65W Fast Charger");
        assertThat(first.path("requestedQuantity").asInt()).isEqualTo(2);
        assertThat(first.path("unitPrice").decimalValue()).isEqualByComparingTo("24.90");
        assertThat(first.path("availableQuantity").isInt()).isTrue();
        assertThat(first.path("lineTotal").decimalValue()).isEqualByComparingTo("49.80");
        assertThat(structured.path("lines").get(1).path("problem").asText()).isEqualTo("not_found");

        // The wire payload conforms to the declared outputSchema (the S6 contract)
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredMap = mapper.convertValue(structured, Map.class);
        var validation = new DefaultJsonSchemaValidator(mapper)
                .validate(orderQuoteMcpTools.getQuoteOrderTool().outputSchema(), structuredMap);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }

    @Test
    @DisplayName("quote_order is listed by the MCP server")
    void quoteOrder_listedByServer() throws Exception {
        String body = callStateless("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
        assertThat(body).contains("\"quote_order\"");
    }

    private String callStateless(String json) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        statelessTransport.service(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
