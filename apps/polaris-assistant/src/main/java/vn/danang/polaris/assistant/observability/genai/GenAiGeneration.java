package vn.danang.polaris.assistant.observability.genai;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records each model provider call made by the annotated method as one GenAI generation (OTel GenAI span
 * {@code generateText <model>} plus {@code gen_ai.client.*} metrics), see {@link GenAiGenerationAspect}.
 * <p>
 * The method's return value must implement {@link vn.danang.polaris.assistant.ai.ModelCallResult}: its
 * {@code modelCall()} says what happened on the wire (model, tokens, finish reason, failure). A result without a
 * model call (local fallback) records nothing. Expressions are SpEL over the method arguments by parameter name
 * ({@code #context}) and, for tags, the return value ({@code #result}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GenAiGeneration {

    /** {@code gen_ai.provider.name}, e.g. {@link GenAiTelemetry#PROVIDER_GEMINI}. */
    String provider();

    /** {@code gen_ai.agent.name}: the label the AI Observability analytics group by. */
    String agent() default GenAiTelemetry.AGENT_NAME;

    /** SpEL for {@code gen_ai.conversation.id}; blank or a null value omits it. */
    String conversationId() default "";

    /** Generation tags: carried on the generation record only, never span attributes or metric labels. */
    Tag[] tags() default {};

    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag {
        String key();

        /** SpEL; a null value omits the tag. */
        String expression();
    }
}
