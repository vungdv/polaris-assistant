package vn.danang.polaris.assistant.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.tools.HttpPolarisMcpClient;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.tools.PolarisMcpProperties;
import vn.danang.polaris.assistant.security.UserContext;

/**
 * Service demonstrating architectural nuances and potential pitfalls in MCP client integrations,
 * particularly thread-affinity loss with {@link UserContext#resolveBearerToken()}.
 */
@Service
public class FakeService {

    private static final Logger log = LoggerFactory.getLogger(FakeService.class);

    private final PolarisMcpClient mcpClient;
    private final UserContext userContext;
    private final PolarisMcpProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public FakeService(PolarisMcpClient mcpClient, UserContext userContext, PolarisMcpProperties properties, ObjectMapper objectMapper) {
        this.mcpClient = mcpClient;
        this.userContext = userContext;
        this.properties = properties;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    /**
     * Demonstrates Issue 1: UserContext.resolveBearerToken() Thread-Affinity Loss.
     * Shows how reading from ThreadLocal SecurityContextHolder breaks when execution hops threads
     * (@Async, CompletableFuture, Virtual Threads), and how to remediate it with context propagation.
     */
    public Map<String, Object> demonstrateIssue1ThreadAffinity() {
        String callerThread = Thread.currentThread().getName();
        String callerToken = userContext.resolveBearerToken();

        // 1. Thread hop via CompletableFuture.supplyAsync (common ForkJoinPool)
        String asyncToken;
        String asyncThread;
        try {
            CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(() -> Map.of(
                    "token", String.valueOf(userContext.resolveBearerToken()),
                    "threadName", Thread.currentThread().getName()
            ));
            Map<String, String> asyncResult = future.get(2L, TimeUnit.SECONDS);
            asyncToken = asyncResult.get("token");
            asyncThread = asyncResult.get("threadName");
        } catch (Exception e) {
            asyncToken = "ERROR: " + e.getMessage();
            asyncThread = "unknown";
        }

        // 2. Thread hop via unadorned Virtual Threads executor
        String vtToken;
        String vtThread;
        try (ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Map<String, String>> vtFuture = vtExecutor.submit(() -> Map.of(
                    "token", String.valueOf(userContext.resolveBearerToken()),
                    "threadName", Thread.currentThread().getName()
            ));
            Map<String, String> vtResult = vtFuture.get(2L, TimeUnit.SECONDS);
            vtToken = String.valueOf(vtResult.get("token"));
            vtThread = String.valueOf(vtResult.get("threadName"));
        } catch (Exception e) {
            vtToken = "ERROR: " + e.getMessage();
            vtThread = "unknown";
        }

        // 3. Remediation via DelegatingSecurityContextExecutorService
        String remediatedToken;
        try {
            ExecutorService baseExecutor = Executors.newVirtualThreadPerTaskExecutor();
            DelegatingSecurityContextExecutorService delegatingExecutor =
                    new DelegatingSecurityContextExecutorService(baseExecutor, SecurityContextHolder.getContext());
            Future<String> future = delegatingExecutor.submit(() -> String.valueOf(userContext.resolveBearerToken()));
            remediatedToken = future.get(2L, TimeUnit.SECONDS);
            delegatingExecutor.shutdown();
            baseExecutor.shutdown();
        } catch (Exception e) {
            remediatedToken = "ERROR: " + e.getMessage();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", "1. UserContext.resolveBearerToken() Thread-Affinity Loss");
        result.put("callerThread", Map.of(
                "thread", callerThread,
                "token", String.valueOf(callerToken)
        ));
        result.put("asyncThreadHop", Map.of(
                "thread", asyncThread,
                "token", String.valueOf(asyncToken),
                "isTokenLost", asyncToken == null || "null".equals(asyncToken)
        ));
        result.put("virtualThreadHop", Map.of(
                "thread", vtThread,
                "token", String.valueOf(vtToken),
                "isTokenLost", vtToken == null || "null".equals(vtToken)
        ));
        result.put("remediationWithContextPropagation", Map.of(
                "tokenPreserved", String.valueOf(remediatedToken)
        ));
        result.put("riskAnalysis", "SecurityContextHolder relies on ThreadLocal by default. Any thread hop (@Async, CompletableFuture, or virtual thread pool) drops the user token, causing downstream HTTP 401. If a static fallback token is configured, it silently masquerades user requests as system requests.");
        return result;
    }

    /**
     * Demonstrates Issue 2: Synchronous java.net.http.HttpClient blocking send() on Virtual Threads.
     */
    public Map<String, Object> demonstrateIssue2VirtualThreads() {
        Runtime.Version version = Runtime.version();
        int concurrentThreads = 5;
        List<Map<String, Object>> taskMetrics = new CopyOnWriteArrayList<>();
        long startTime = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentThreads; i++) {
                final int taskId = i + 1;
                futures.add(executor.submit(() -> {
                    long taskStart = System.currentTimeMillis();
                    boolean isVirtual = Thread.currentThread().isVirtual();
                    String threadDescription = Thread.currentThread().toString();
                    List<Tool> tools = mcpClient.listAvailableTools();
                    long taskDuration = System.currentTimeMillis() - taskStart;
                    taskMetrics.add(Map.of(
                            "taskId", taskId,
                            "isVirtualThread", isVirtual,
                            "threadDescription", threadDescription,
                            "toolsFound", tools != null ? tools.size() : 0,
                            "durationMs", taskDuration
                    ));
                }));
            }
            for (Future<?> f : futures) {
                f.get(5L, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Issue 2 execution encountered: {}", e.getMessage());
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", "2. Synchronous java.net.http.HttpClient blocking send() on Virtual Threads");
        result.put("jdkVersion", version.toString());
        result.put("concurrentVirtualThreads", concurrentThreads);
        result.put("totalDurationMs", totalDuration);
        result.put("taskMetrics", taskMetrics);
        result.put("analysis", "java.net.http.HttpClient.send() synchronously blocks the calling virtual thread. In early JDK 21 builds (<21.0.3), internal synchronized blocks during TLS handshakes and connection-pool locking pinned carrier threads under load. Recommendation: Verify pinning via -Djdk.tracePinnedThreads=full or migrate to non-blocking sendAsync().");
        return result;
    }

    /**
     * Demonstrates Issue 3: Static 'id': '1' in tools/list Correlation Fragility.
     */
    public Map<String, Object> demonstrateIssue3StaticId() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", "3. Static 'id': '1' in tools/list Correlation Fragility");
        result.put("toolsListId", "1 (hardcoded static literal in listAvailableTools())");
        result.put("toolsCallId", UUID.randomUUID().toString() + " (dynamic random UUID in callTool())");
        result.put("fragilityAnalysis", "Hardcoding 'id': '1' in listAvailableTools() is fragile. If transport is adapted to multiplexed HTTP/2 streams, WebSockets, or pipelined JSON-RPC sessions, identical request IDs cause response correlation collisions. In addition, distributed logs cannot correlate backend responses to specific caller spans.");
        result.put("recommendedFix", "Update listAvailableTools() to use UUID.randomUUID().toString() identically to callTool().");
        return result;
    }

    /**
     * Demonstrates Issue 4: Broad catch (Exception e) Swallows Timeout & Connectivity Nuance.
     */
    public Map<String, Object> demonstrateIssue4BroadCatch() {
        PolarisMcpProperties isolatedProps = new PolarisMcpProperties();
        isolatedProps.getCore().setUrl("http://192.0.2.1:54321/mcp");
        isolatedProps.getCore().setTimeoutSeconds(3);

        HttpPolarisMcpClient isolatedClient = new HttpPolarisMcpClient(isolatedProps, objectMapper);
        long startTime = System.currentTimeMillis();
        List<Tool> listResult = isolatedClient.listAvailableTools();
        CallToolResult callResult = isolatedClient.callTool("search_products", Map.of("query", "test"));
        long duration = System.currentTimeMillis() - startTime;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", "4. Broad catch (Exception e) Swallows Timeout & Connectivity Nuance");
        result.put("durationMs", duration);
        result.put("listAvailableToolsResult", Map.of(
                "toolsReturnedCount", listResult.size(),
                "defect", "Returned empty list []! Swallowed HttpTimeoutException/ConnectException and masqueraded an infrastructure failure as 'zero tools registered'."
        ));
        result.put("callToolResult", Map.of(
                "isError", callResult.isError(),
                "content", callResult.content() != null && !callResult.content().isEmpty()
                        ? callResult.content().get(0).toString()
                        : "empty",
                "defect", "Swallowed network exception into generic error string without structured error classification (e.g. errorType=TIMEOUT or CONNECTIVITY), preventing circuit breakers and retry policies from operating deterministically."
        ));
        result.put("recommendedFix", "Catch HttpTimeoutException and ConnectException explicitly to log distinct alertable errors and attach structured metadata (e.g. errorType=TIMEOUT).");
        return result;
    }

    /**
     * Consolidated demonstration of all 4 issues.
     */
    public Map<String, Object> demonstrateAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("service", "polaris-assistant");
        result.put("description", "Demonstration report for HttpPolarisMcpClient issues");
        result.put("issue1_threadAffinity", demonstrateIssue1ThreadAffinity());
        result.put("issue2_virtualThreads", demonstrateIssue2VirtualThreads());
        result.put("issue3_staticId", demonstrateIssue3StaticId());
        result.put("issue4_broadCatch", demonstrateIssue4BroadCatch());
        return result;
    }
}
