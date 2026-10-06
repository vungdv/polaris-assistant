package vn.danang.polaris.assistant.observability.genai;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import com.grafana.agento11y.sdk.Agento11yClient;
import com.grafana.agento11y.sdk.GenerationRecorder;
import com.grafana.agento11y.sdk.GenerationResult;
import com.grafana.agento11y.sdk.GenerationStart;
import com.grafana.agento11y.sdk.ModelRef;
import com.grafana.agento11y.sdk.TokenUsage;

import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelCallResult;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;

/**
 * Implements {@link GenAiGeneration}: turns the {@link ModelCall} on an annotated method's result into one
 * generation recorded through the agento11y SDK, so provider clients stay free of telemetry code.
 * <p>
 * The generation is recorded after the method returns, with the call's measured start time. That loses
 * nothing: the SDK never makes its span current during the call anyway. A failed provider call is thrown as an
 * exception that is itself a {@link ModelCallResult} (e.g. {@code ModelUnavailableException}); its
 * {@link ModelCall#failure()} is recorded and the exception rethrown. Any other exception propagates unrecorded:
 * it means no provider call result exists (e.g. a missing API key rejected up front).
 */
@Aspect
@Component
@Order(20)
public class GenAiGenerationAspect {

    private static final Logger log = LoggerFactory.getLogger(GenAiGenerationAspect.class);

    private final Agento11yClient genAiTelemetry;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public GenAiGenerationAspect(Agento11yClient genAiTelemetry) {
        this.genAiTelemetry = genAiTelemetry;
    }

    @Around("@annotation(generation)")
    public Object recordGeneration(ProceedingJoinPoint joinPoint, GenAiGeneration generation) throws Throwable {
        Instant startedAt = Instant.now();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            if (failure instanceof ModelCallResult failedCall) {
                recordSafely(joinPoint, generation, failedCall, startedAt, null);
            }
            throw failure;
        }
        if (result instanceof ModelCallResult callResult) {
            recordSafely(joinPoint, generation, callResult, startedAt, result);
        }
        return result;
    }

    private void recordSafely(ProceedingJoinPoint joinPoint, GenAiGeneration generation, ModelCallResult callResult,
            Instant startedAt, @Nullable Object result) {
        if (callResult.modelCall() == null) {
            return;
        }
        try {
            record(generation, callResult.modelCall(), startedAt, evaluationContext(joinPoint, result));
        } catch (RuntimeException e) {
            log.warn("Failed to record GenAI generation for {}: {}", joinPoint.getSignature().toShortString(), e.getMessage());
        }
    }

    private void record(GenAiGeneration generation, ModelCall call, Instant startedAt, EvaluationContext context) {
        GenerationStart start = new GenerationStart()
                .setAgentName(generation.agent())
                .setModel(new ModelRef().setProvider(generation.provider()).setName(call.requestModel()))
                .setStartedAt(startedAt)
                .setTags(tags(generation, context));
        String conversationId = evaluate(generation.conversationId(), context);
        if (conversationId != null && !conversationId.isBlank()) {
            start.setConversationId(conversationId);
        }

        try (GenerationRecorder recorder = genAiTelemetry.startGeneration(start)) {
            if (call.isFailed()) {
                recorder.setCallError(call.failure());
            }
            GenerationResult result = new GenerationResult()
                    .setUsage(tokenUsage(call.usage()))
                    .setCompletedAt(Instant.now());
            if (call.responseModel() != null) {
                result.setResponseModel(call.responseModel());
            }
            if (call.responseId() != null) {
                result.setResponseId(call.responseId());
            }
            if (call.finishReason() != null) {
                result.setStopReason(call.finishReason());
            }
            recorder.setResult(result);
        }
    }

    /** Providers report prompt tokens including cached ones: OTel "inclusive" input semantics. */
    private static TokenUsage tokenUsage(ModelTokenUsage usage) {
        return new TokenUsage()
                .setInputTokens(usage.input())
                .setOutputTokens(usage.output())
                .setReasoningTokens(usage.reasoning())
                .setCacheReadInputTokens(usage.cachedInput())
                .setInputSemantics(TokenUsage.TokenInputSemantics.INCLUSIVE);
    }

    private Map<String, String> tags(GenAiGeneration generation, EvaluationContext context) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (GenAiGeneration.Tag tag : generation.tags()) {
            String value = evaluate(tag.expression(), context);
            if (value != null) {
                tags.put(tag.key(), value);
            }
        }
        return tags;
    }

    private EvaluationContext evaluationContext(ProceedingJoinPoint joinPoint, Object result) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
        context.setVariable("result", result);
        return context;
    }

    @Nullable
    private String evaluate(String expression, EvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object value = expressionParser.parseExpression(expression).getValue(context);
            return value != null ? value.toString() : null;
        } catch (RuntimeException e) {
            log.debug("Failed to evaluate SpEL expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }
}
