package vn.danang.polaris.assistant.observability.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a tag to be attached to a distributed tracing span managed by {@link CustomNextSpan}.
 * <p>
 * Can be declared within {@link CustomNextSpan#tags()} or {@link CustomNextSpan#resultTags()} on a method, or directly on a method parameter:
 * <ul>
 *   <li><b>Method-level static tag:</b> {@code @SpanTag(key = "service.name", value = "billing")}</li>
 *   <li><b>Method-level argument binding:</b> {@code @SpanTag(key = "order.id", expression = "#order.id")}</li>
 *   <li><b>Method-level result binding:</b> {@code @SpanTag(key = "mcp.tool_count", expression = "#result?.size()")}</li>
 *   <li><b>Parameter-level tag:</b> {@code void doWork(@SpanTag("user.id") String userId)}</li>
 *   <li><b>Parameter-level expression:</b> {@code void doWork(@SpanTag(key = "user.id", expression = "id") User user)}</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SpanTag {

    /**
     * Tag key or static value depending on context.
     * <ul>
     *   <li>When {@link #key()} is specified: represents the static tag value.</li>
     *   <li>When used on a parameter without {@link #key()}: represents the tag key name.</li>
     * </ul>
     */
    String value() default "";

    /**
     * The tag key or name.
     */
    String key() default "";

    /**
     * SpEL expression to evaluate against method arguments or parameter (e.g. {@code "#request.sessionId()"}).
     */
    String expression() default "";
}
