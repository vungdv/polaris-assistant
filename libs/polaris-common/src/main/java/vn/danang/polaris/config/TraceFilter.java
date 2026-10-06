package vn.danang.polaris.config;

import java.io.IOException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
class TraceIdFilter extends OncePerRequestFilter {
    
    @Nullable
    private final Tracer tracer;

    public TraceIdFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String traceId = getTraceId();
        if (traceId != null) {
            response.setHeader("X-Trace-Id", traceId);
        }
        filterChain.doFilter(request, response);
    }

    private @Nullable String getTraceId() {
        if (this.tracer == null) {
            return null;
        }
        TraceContext context = this.tracer.currentTraceContext().context();
        return context != null ? context.traceId() : null;
    }
}