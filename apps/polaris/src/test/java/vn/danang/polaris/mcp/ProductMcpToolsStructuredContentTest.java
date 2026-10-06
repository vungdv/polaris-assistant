package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;

/**
 * S8 (G8): the catalog tools return {@code structuredContent} for product cards next to the model's text.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class ProductMcpToolsStructuredContentTest {

    private static final List<String> PRODUCT_FIELDS =
            List.of("sku", "name", "category", "price", "stockQuantity", "available");

    @Autowired
    private ProductMcpTools productMcpTools;

    @Autowired
    private HttpServletStatelessServerTransport statelessTransport;

    @Autowired
    private ProductRepository productRepository;

    @Test
    @DisplayName("search_available_products and get_product_by_sku declare an outputSchema")
    @SuppressWarnings("unchecked")
    void outputSchemaContract() {
        Map<String, Object> searchProps =
                (Map<String, Object>) productMcpTools.getSearchProductsTool().outputSchema().get("properties");
        assertThat(searchProps).containsOnlyKeys("products", "totalElements");
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) searchProps.get("products")).get("items");
        assertThat((Map<String, Object>) items.get("properties")).containsOnlyKeys(PRODUCT_FIELDS.toArray(String[]::new));

        Map<String, Object> skuProps =
                (Map<String, Object>) productMcpTools.getProductBySkuTool().outputSchema().get("properties");
        assertThat(skuProps).containsOnlyKeys(PRODUCT_FIELDS.toArray(String[]::new));
    }

    @Test
    @DisplayName("search_available_products returns structuredContent with the same products as the text")
    void search_structuredContent() {
        McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(Map.of("query", "Earbuds"));

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(ProductToolContent.SearchResult.class);
        ProductToolContent.SearchResult search = (ProductToolContent.SearchResult) result.structuredContent();
        assertThat(search.totalElements()).isEqualTo(search.products().size());
        ProductToolContent.Product earbuds = search.products().stream()
                .filter(p -> p.sku().equals("NG-EARBUD-01")).findFirst().orElseThrow();
        assertThat(earbuds.name()).isEqualTo("Nova Wireless Earbuds");
        assertThat(earbuds.category()).isNotBlank();
        assertThat(earbuds.price()).isEqualByComparingTo("49.90");
        assertThat(earbuds.stockQuantity()).isEqualTo(120);
        assertThat(earbuds.available()).isTrue();

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("[NG-EARBUD-01] Nova Wireless Earbuds");
    }

    @Test
    @DisplayName("search_available_products with no match returns an empty product list, not an error")
    void search_empty_structuredContent() {
        McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(
                Map.of("query", "DEFINITELY_NON_EXISTENT_PRODUCT_12345"));

        assertThat(result.isError()).isFalse();
        ProductToolContent.SearchResult search = (ProductToolContent.SearchResult) result.structuredContent();
        assertThat(search.products()).isEmpty();
        assertThat(search.totalElements()).isZero();
    }

    @Test
    @DisplayName("errors carry no structuredContent")
    void errors_noStructuredContent() {
        McpSchema.CallToolResult badPage = productMcpTools.searchAvailableProducts(Map.of("page", -5));
        assertThat(badPage.isError()).isTrue();
        assertThat(badPage.structuredContent()).isNull();

        McpSchema.CallToolResult unknownSku = productMcpTools.getProductBySku(Map.of("sku", "NONEXISTENT-999"));
        assertThat(unknownSku.isError()).isTrue();
        assertThat(unknownSku.structuredContent()).isNull();
    }

    @Test
    @DisplayName("get_product_by_sku returns the product as structuredContent")
    void getProductBySku_structuredContent() {
        McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of("sku", "NG-EARBUD-01"));

        assertThat(result.isError()).isFalse();
        ProductToolContent.Product product = (ProductToolContent.Product) result.structuredContent();
        assertThat(product.sku()).isEqualTo("NG-EARBUD-01");
        assertThat(product.name()).isEqualTo("Nova Wireless Earbuds");
        assertThat(product.price()).isEqualByComparingTo("49.90");
        assertThat(product.stockQuantity()).isEqualTo(120);
        assertThat(product.available()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("- Price: $49.90");
    }

    @Test
    @DisplayName("search_available_products over MCP wire: structuredContent is exact and conforms to the outputSchema")
    void search_overWire_conformsToOutputSchema() throws Exception {
        JsonNode result = callTool("search_available_products", "{\"query\":\"Earbuds\"}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        JsonNode structured = result.path("structuredContent");
        JsonNode first = structured.path("products").get(0);
        assertThat(first.path("sku").asText()).isEqualTo("NG-EARBUD-01");
        assertThat(first.path("price").decimalValue()).isEqualByComparingTo("49.90");
        assertThat(first.path("stockQuantity").asInt()).isEqualTo(120);
        assertThat(first.path("available").asBoolean()).isTrue();
        assertConformsTo(productMcpTools.getSearchProductsTool().outputSchema(), structured);
    }

    @Test
    @DisplayName("get_product_by_sku over MCP wire: structuredContent conforms to the outputSchema")
    void getProductBySku_overWire_conformsToOutputSchema() throws Exception {
        JsonNode result = callTool("get_product_by_sku", "{\"sku\":\"NG-EARBUD-01\"}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("name").asText()).isEqualTo("Nova Wireless Earbuds");
        assertThat(structured.path("price").decimalValue()).isEqualByComparingTo("49.90");
        assertConformsTo(productMcpTools.getProductBySkuTool().outputSchema(), structured);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // the MCP server reads on its own thread: commit the row
    @DisplayName("a product without a category over MCP wire: isError=false and category null, conforming to the outputSchema")
    void productWithoutCategory_overWire_categoryNull() throws Exception {
        String sku = "S8-NOCAT-" + UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product();
        product.setSku(sku);
        product.setName("Uncategorised " + sku);
        product.setPrice(new BigDecimal("9.90"));
        product.setStockQty(3);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        Long id = productRepository.save(product).getId();
        try {
            JsonNode bySku = callTool("get_product_by_sku", "{\"sku\":\"" + sku + "\"}");
            assertThat(bySku.path("isError").asBoolean()).isFalse();
            assertThat(bySku.path("structuredContent").has("category")).isTrue();
            assertThat(bySku.path("structuredContent").path("category").isNull()).isTrue();
            assertConformsTo(productMcpTools.getProductBySkuTool().outputSchema(), bySku.path("structuredContent"));

            JsonNode search = callTool("search_available_products", "{\"query\":\"" + sku + "\"}");
            assertThat(search.path("isError").asBoolean()).isFalse();
            JsonNode found = search.path("structuredContent").path("products").get(0);
            assertThat(found.path("sku").asText()).isEqualTo(sku);
            assertThat(found.path("category").isNull()).isTrue();
            assertConformsTo(productMcpTools.getSearchProductsTool().outputSchema(), search.path("structuredContent"));
        } finally {
            productRepository.deleteById(id);
        }
    }

    private static void assertConformsTo(Map<String, Object> schema, JsonNode structured) {
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredMap = mapper.convertValue(structured, Map.class);
        var validation = new DefaultJsonSchemaValidator(mapper).validate(schema, structuredMap);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }

    private JsonNode callTool(String name, String argumentsJson) throws Exception {
        String json = """
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, argumentsJson);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        statelessTransport.service(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        return new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("result");
    }
}
