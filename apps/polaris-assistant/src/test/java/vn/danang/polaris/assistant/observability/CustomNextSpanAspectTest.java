package vn.danang.polaris.assistant.observability;

import java.lang.reflect.Method;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

class CustomNextSpanAspectTest {

    private Tracer tracer;
    private Span span;
    private Tracer.SpanInScope spanInScope;
    private ProceedingJoinPoint joinPoint;
    private MethodSignature methodSignature;
    private DummySampleService sampleService;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        spanInScope = mock(Tracer.SpanInScope.class);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);

        joinPoint = mock(ProceedingJoinPoint.class);
        methodSignature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);

        sampleService = new DummySampleService();
        when(joinPoint.getTarget()).thenReturn(sampleService);
    }

    private void setupMethod(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DummySampleService.class.getMethod(methodName, parameterTypes);
        when(methodSignature.getMethod()).thenReturn(method);
    }

    // =========================================================================
    // 1. Happy path — span creation and tag binding
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given annotated method, when executed, then creates span with custom name, static tags, and SpEL dynamic tags")
        void applies_static_and_dynamic_spel_tags_to_span() throws Throwable {
            setupMethod("annotatedMethod", DummyOrder.class, String.class);
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            DummyOrder order = new DummyOrder("ORD-999", 150.0);
            when(joinPoint.getArgs()).thenReturn(new Object[]{order, "vip-user"});
            when(joinPoint.proceed()).thenReturn("processed");

            Object result = aspect.traceNextSpan(joinPoint);

            assertThat(result).isEqualTo("processed");
            verify(tracer).nextSpan();
            verify(span).name("order.process");
            verify(span).tag("service.name", "billing");
            verify(span).tag("order.id", "ORD-999");
            verify(span).tag("order.amount", "150.0");
            verify(span).tag("user.tier", "vip-user");
            verify(span).start();
            verify(tracer).withSpan(span);
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given method with parameter-level @SpanTag, when executed, then binds parameter values to span tags")
        void extracts_tags_from_parameter_level_span_tag_annotations() throws Throwable {
            setupMethod("parameterAnnotatedMethod", String.class, DummyOrder.class);
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            DummyOrder order = new DummyOrder("ORD-456", 75.0);
            when(joinPoint.getArgs()).thenReturn(new Object[]{"client-1", order});
            when(joinPoint.proceed()).thenReturn("done");

            aspect.traceNextSpan(joinPoint);

            verify(span).name("client.order");
            verify(span).tag("client.id", "client-1");
            verify(span).tag("target.order.id", "ORD-456");
        }

        @Test
        @DisplayName("Given @CustomNextSpan without custom name, when executed, then falls back to ClassName.methodName")
        void falls_back_to_method_name_when_span_name_omitted() throws Throwable {
            setupMethod("unnamedSpanMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn("ok");

            aspect.traceNextSpan(joinPoint);

            verify(span).name("DummySampleService.unnamedSpanMethod");
        }

        @Test
        @DisplayName("Given method returning collection with #result?.size() tag, when executed, then tags span with collection size")
        void applies_result_expression_tag_to_span_from_tags_attribute() throws Throwable {
            setupMethod("discoverToolsMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn(List.of("search_products", "order_create"));

            aspect.traceNextSpan(joinPoint);

            verify(span).name("mcp.polaris.discovery");
            verify(span).tag("mcp.tool_count", "2");
            verify(span).end();
        }

        @Test
        @DisplayName("Given method with resultTags expression, when executed, then tags span evaluating directly against result object")
        void applies_result_tag_evaluating_directly_against_result_object() throws Throwable {
            setupMethod("discoverToolsWithResultTagsMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn(List.of("search_products", "order_create", "cancel_order"));

            aspect.traceNextSpan(joinPoint);

            verify(span).name("mcp.polaris.discovery.resultTags");
            verify(span).tag("mcp.tool_count", "3");
            verify(span).end();
        }

        @Test
        @DisplayName("Given method combining argument and result in SpEL expression, when executed, then evaluates both")
        void combines_method_arguments_and_result_in_post_execution_expression() throws Throwable {
            setupMethod("calculateMethod", String.class);
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{"user-77"});
            when(joinPoint.proceed()).thenReturn(42);

            aspect.traceNextSpan(joinPoint);

            verify(span).name("service.calculate");
            verify(span).tag("user.id", "user-77");
            verify(span).tag("calc.result", "user-77:42");
            verify(span).end();
        }
    }

    // =========================================================================
    // 2. Invalid input & failure handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & failure handling")
    class InvalidInput {

        @Test
        @DisplayName("Given target method throws exception, when executed, then records error and exception on span and rethrows")
        void tags_error_and_records_exception_on_span_when_target_method_throws() throws Throwable {
            setupMethod("unnamedSpanMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            RuntimeException ex = new RuntimeException("Database timeout");
            when(joinPoint.proceed()).thenThrow(ex);

            assertThatThrownBy(() -> aspect.traceNextSpan(joinPoint))
                    .isSameAs(ex);

            verify(span).error(ex);
            verify(span).tag("error", "true");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given target method throws exception, when executed, does not apply result tags to span")
        void does_not_apply_result_tags_when_target_method_throws() throws Throwable {
            setupMethod("discoverToolsMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            RuntimeException ex = new RuntimeException("Discovery failed");
            when(joinPoint.proceed()).thenThrow(ex);

            assertThatThrownBy(() -> aspect.traceNextSpan(joinPoint))
                    .isSameAs(ex);

            verify(span).error(ex);
            verify(span).tag("error", "true");
            verify(span, never()).tag(eq("mcp.tool_count"), any());
            verify(span).end();
        }

        @Test
        @DisplayName("Given invalid SpEL expression on result tag, when executed, does not fail method execution")
        void handles_invalid_spel_expression_on_result_without_failing_method_execution() throws Throwable {
            setupMethod("badSpelMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn("done");

            Object result = aspect.traceNextSpan(joinPoint);

            assertThat(result).isEqualTo("done");
            verify(span, never()).tag(eq("invalid.key"), any());
            verify(span).end();
        }
    }

    // =========================================================================
    // 3. Edge cases — null arguments, absent tracer, ObjectProvider injection
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given null arguments for SpEL expressions, when evaluated, then handles null-safe navigation and fallback safely")
        void evaluates_spel_expressions_with_null_safe_navigation_and_fallback() throws Throwable {
            setupMethod("annotatedMethod", DummyOrder.class, String.class);
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{null, null});
            when(joinPoint.proceed()).thenReturn("processed-null");

            Object result = aspect.traceNextSpan(joinPoint);

            assertThat(result).isEqualTo("processed-null");
            verify(span).name("order.process");
            verify(span).tag("service.name", "billing");
            verify(span, never()).tag(eq("order.id"), any());
            verify(span).tag("user.tier", "standard");
            verify(span).end();
        }

        @Test
        @DisplayName("Given null tracer, when aspect executes, then proceeds directly without throwing exception")
        void proceeds_directly_without_exceptions_when_tracer_is_absent() throws Throwable {
            setupMethod("unnamedSpanMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect((Tracer) null);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn("no-tracer");

            Object result = aspect.traceNextSpan(joinPoint);

            assertThat(result).isEqualTo("no-tracer");
            verify(tracer, never()).nextSpan();
        }

        @Test
        @DisplayName("Given ObjectProvider constructor, when instantiated, then extracts Tracer bean")
        @SuppressWarnings("unchecked")
        void extracts_tracer_from_object_provider_in_constructor() throws Throwable {
            setupMethod("unnamedSpanMethod");
            ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(tracer);

            CustomNextSpanAspect aspect = new CustomNextSpanAspect(provider);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn("ok");

            aspect.traceNextSpan(joinPoint);

            verify(tracer).nextSpan();
        }

        @Test
        @DisplayName("Given target method returns null, when null-safe result expression evaluated, safely omits tag")
        void handles_null_result_safely_without_attaching_result_tag() throws Throwable {
            setupMethod("discoverToolsMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn(null);

            Object result = aspect.traceNextSpan(joinPoint);

            assertThat(result).isNull();
            verify(span, never()).tag(eq("mcp.tool_count"), any());
            verify(span).end();
        }

        @Test
        @DisplayName("Given target method returns empty collection, when evaluated with #result?.size(), tags zero")
        void tags_zero_when_result_is_empty_collection() throws Throwable {
            setupMethod("discoverToolsMethod");
            CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

            when(joinPoint.getArgs()).thenReturn(new Object[]{});
            when(joinPoint.proceed()).thenReturn(List.of());

            aspect.traceNextSpan(joinPoint);

            verify(span).tag("mcp.tool_count", "0");
            verify(span).end();
        }
    }

    // =========================================================================
    // Dummy Test Classes
    // =========================================================================

    public record DummyOrder(String id, double amount) {}

    public static class DummySampleService {

        @CustomNextSpan(
                name = "order.process",
                tags = {
                    @SpanTag(key = "service.name", value = "billing"),
                    @SpanTag(key = "order.id", expression = "#order?.id()"),
                    @SpanTag(key = "order.amount", expression = "#order?.amount()"),
                    @SpanTag(key = "user.tier", expression = "#userId != null && !#userId.isBlank() ? #userId : 'standard'")
                }
        )
        public String annotatedMethod(DummyOrder order, String userId) {
            return "processed";
        }

        @CustomNextSpan("client.order")
        public String parameterAnnotatedMethod(
                @SpanTag("client.id") String clientId,
                @SpanTag(key = "target.order.id", expression = "id()") DummyOrder order) {
            return "done";
        }

        @CustomNextSpan
        public String unnamedSpanMethod() {
            return "ok";
        }

        @CustomNextSpan(
                name = "mcp.polaris.discovery",
                tags = {
                    @SpanTag(key = "mcp.tool_count", expression = "#result?.size()")
                }
        )
        public List<String> discoverToolsMethod() {
            return List.of("search_products", "order_create");
        }

        @CustomNextSpan(
                name = "mcp.polaris.discovery.resultTags",
                resultTags = {
                    @SpanTag(key = "mcp.tool_count", expression = "size()")
                }
        )
        public List<String> discoverToolsWithResultTagsMethod() {
            return List.of("search_products", "order_create", "cancel_order");
        }

        @CustomNextSpan(
                name = "service.calculate",
                tags = {
                    @SpanTag(key = "user.id", expression = "#userId"),
                    @SpanTag(key = "calc.result", expression = "#userId + ':' + #result")
                }
        )
        public int calculateMethod(String userId) {
            return 42;
        }

        @CustomNextSpan(
                name = "result.bad.spel",
                resultTags = {
                    @SpanTag(key = "invalid.key", expression = "invalidSpelSyntax(()")
                }
        )
        public String badSpelMethod() {
            return "done";
        }
    }
}
