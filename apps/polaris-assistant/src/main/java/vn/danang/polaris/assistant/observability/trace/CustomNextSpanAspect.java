package vn.danang.polaris.assistant.observability.trace;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.regex.Pattern;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;

/**
 * Generic AOP aspect implementing {@link CustomNextSpan}.
 * <p>
 * Manages the distributed tracing span lifecycle (creation, tagging, activation, error capturing,
 * and completion) around annotated methods. Evaluates static values and dynamic SpEL expressions
 * bound to method arguments or execution results to attach context-rich tags to the span.
 *
 */
@Aspect
@Component
@Order(10)
public class CustomNextSpanAspect {

    private static final Logger log = LoggerFactory.getLogger(CustomNextSpanAspect.class);
    private static final Pattern RESULT_PATTERN = Pattern.compile("(#result|#returnObject)\\b");

    private final Optional<Tracer> tracer;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Autowired
    public CustomNextSpanAspect(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
    }

    public CustomNextSpanAspect(@Nullable Tracer tracer) {
        this.tracer = Optional.ofNullable(tracer);
    }

    @Around("@annotation(vn.danang.polaris.assistant.observability.trace.CustomNextSpan)")
    public Object traceNextSpan(ProceedingJoinPoint joinPoint) throws Throwable {
        if (tracer.isEmpty()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        CustomNextSpan customNextSpan = resolveNextSpanAnnotation(method, joinPoint.getTarget());

        if (customNextSpan == null) {
            return joinPoint.proceed();
        }

        Tracer activeTracer = tracer.get();
        String spanName = resolveSpanName(customNextSpan, method);

        Span span = activeTracer.nextSpan().name(spanName);
        StandardEvaluationContext context = buildEvaluationContext(method, joinPoint.getArgs(), joinPoint.getTarget());
        applyPreExecutionTags(span, customNextSpan, method, joinPoint.getArgs(), context);
        span.start();

        try (Tracer.SpanInScope ws = activeTracer.withSpan(span)) {
            Object result = joinPoint.proceed();
            try {
                applyPostExecutionTags(span, customNextSpan, result, context);
            } catch (Exception e) {
                log.debug("Failed to apply post-execution span tags: {}", e.getMessage(), e);
            }
            return result;
        } catch (Throwable ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    private CustomNextSpan resolveNextSpanAnnotation(Method method, Object target) {
        CustomNextSpan annotation = method.getAnnotation(CustomNextSpan.class);
        if (annotation != null) {
            return annotation;
        }
        if (target != null) {
            try {
                Method targetMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());
                return targetMethod.getAnnotation(CustomNextSpan.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private String resolveSpanName(CustomNextSpan customNextSpan, Method method) {
        if (customNextSpan != null) {
            if (customNextSpan.name() != null && !customNextSpan.name().isBlank()) {
                return customNextSpan.name();
            }
            if (customNextSpan.value() != null && !customNextSpan.value().isBlank()) {
                return customNextSpan.value();
            }
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private void applyPreExecutionTags(Span span, CustomNextSpan customNextSpan, Method method, Object[] args, StandardEvaluationContext context) {
        // 1. Method-level tags defined on @CustomNextSpan(tags = { ... }) that do not reference result
        SpanTag[] methodTags = customNextSpan.tags();
        if (methodTags != null) {
            for (SpanTag tag : methodTags) {
                if (isResultExpression(tag.expression())) {
                    continue;
                }
                String key = resolveKey(tag, null);
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = resolveMethodTagValue(tag, context);
                if (value != null && !value.isBlank()) {
                    span.tag(key, value);
                }
            }
        }

        // 2. Parameter-level tags defined directly on parameters with @SpanTag
        Parameter[] parameters = method.getParameters();
        if (parameters != null && args != null) {
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                Parameter param = parameters[i];
                SpanTag paramTag = param.getAnnotation(SpanTag.class);
                if (paramTag != null) {
                    String key = resolveKey(paramTag, param);
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String value = resolveParamTagValue(paramTag, args[i], context);
                    if (value != null && !value.isBlank()) {
                        span.tag(key, value);
                    }
                }
            }
        }
    }

    private void applyPostExecutionTags(Span span, CustomNextSpan customNextSpan, @Nullable Object result, StandardEvaluationContext context) {
        context.setVariable("result", result);
        context.setVariable("returnObject", result);

        // 1. Method-level tags in tags() that reference result
        SpanTag[] methodTags = customNextSpan.tags();
        if (methodTags != null) {
            for (SpanTag tag : methodTags) {
                if (!isResultExpression(tag.expression())) {
                    continue;
                }
                String key = resolveKey(tag, null);
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = resolveResultTagValue(tag, result, context);
                if (value != null && !value.isBlank()) {
                    span.tag(key, value);
                }
            }
        }

        // 2. Result tags defined on @CustomNextSpan(resultTags = { ... })
        SpanTag[] resultTags = customNextSpan.resultTags();
        if (resultTags != null) {
            for (SpanTag tag : resultTags) {
                String key = resolveKey(tag, null);
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = resolveResultTagValue(tag, result, context);
                if (value != null && !value.isBlank()) {
                    span.tag(key, value);
                }
            }
        }
    }

    private boolean isResultExpression(@Nullable String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        return RESULT_PATTERN.matcher(expression).find();
    }

    private StandardEvaluationContext buildEvaluationContext(Method method, Object[] args, Object target) {
        StandardEvaluationContext context = new StandardEvaluationContext(target);
        if (args == null || args.length == 0) {
            return context;
        }

        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (paramNames != null && i < paramNames.length && paramNames[i] != null) {
                context.setVariable(paramNames[i], arg);
            }
            context.setVariable("p" + i, arg);
            context.setVariable("a" + i, arg);
        }
        context.setVariable("args", args);
        return context;
    }

    private String resolveKey(SpanTag tag, @Nullable Parameter param) {
        if (tag.key() != null && !tag.key().isBlank()) {
            return tag.key();
        }
        if (tag.value() != null && !tag.value().isBlank()) {
            return tag.value();
        }
        if (param != null) {
            return param.getName();
        }
        return null;
    }

    private String resolveMethodTagValue(SpanTag tag, EvaluationContext context) {
        if (tag.expression() != null && !tag.expression().isBlank()) {
            try {
                Expression expr = expressionParser.parseExpression(tag.expression());
                Object val = expr.getValue(context);
                return val != null ? val.toString() : null;
            } catch (Exception e) {
                log.debug("Failed to evaluate SpEL expression '{}' for tag: {}", tag.expression(), e.getMessage());
                return null;
            }
        }
        if (tag.key() != null && !tag.key().isBlank() && tag.value() != null && !tag.value().isBlank()) {
            return tag.value();
        }
        return null;
    }

    private String resolveParamTagValue(SpanTag tag, @Nullable Object argValue, EvaluationContext context) {
        if (tag.expression() != null && !tag.expression().isBlank()) {
            try {
                Expression expr = expressionParser.parseExpression(tag.expression());
                Object val = (argValue != null) ? expr.getValue(context, argValue) : expr.getValue(context);
                return val != null ? val.toString() : null;
            } catch (Exception e) {
                log.debug("Failed to evaluate SpEL expression '{}' on parameter: {}", tag.expression(), e.getMessage());
                return null;
            }
        }
        return argValue != null ? argValue.toString() : null;
    }

    private String resolveResultTagValue(SpanTag tag, @Nullable Object result, EvaluationContext context) {
        if (tag.expression() != null && !tag.expression().isBlank()) {
            try {
                Expression expr = expressionParser.parseExpression(tag.expression());
                Object val = (result != null) ? expr.getValue(context, result) : expr.getValue(context);
                return val != null ? val.toString() : null;
            } catch (Exception e) {
                log.debug("Failed to evaluate SpEL expression '{}' on result: {}", tag.expression(), e.getMessage());
                return null;
            }
        }
        if (tag.key() != null && !tag.key().isBlank() && tag.value() != null && !tag.value().isBlank()) {
            return tag.value();
        }
        return result != null ? result.toString() : null;
    }
}
