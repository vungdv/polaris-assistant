package vn.danang.polaris.assistant.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.assistant.service.FakeService;

/**
 * Controller exposing endpoints that demonstrate architectural issues in HttpPolarisMcpClient
 * and UserContext thread-affinity behavior.
 */
@RestController
@RequestMapping("/api/v1/assistant/demo")
@Tag(name = "MCP Issues Demonstration", description = "Endpoints demonstrating HttpPolarisMcpClient architectural issues")
public class FakeController {

    private final FakeService fakeService;

    public FakeController(FakeService fakeService) {
        this.fakeService = fakeService;
    }

    @GetMapping("/all")
    @Operation(summary = "Demonstrate all 4 issues", description = "Executes real code demonstrations for all 4 MCP client issues and returns a consolidated report.")
    public ResponseEntity<Map<String, Object>> demonstrateAll() {
        return ResponseEntity.ok(fakeService.demonstrateAll());
    }

    @GetMapping("/issue-1-thread-affinity")
    @Operation(summary = "Demonstrate Issue 1: Thread-Affinity Loss", description = "Demonstrates how UserContext.resolveBearerToken() drops token across async boundaries.")
    public ResponseEntity<Map<String, Object>> demonstrateIssue1() {
        return ResponseEntity.ok(fakeService.demonstrateIssue1ThreadAffinity());
    }

    @GetMapping("/issue-2-virtual-threads")
    @Operation(summary = "Demonstrate Issue 2: Virtual Thread Blocking & Pinning", description = "Demonstrates synchronous blocking calls on virtual threads and reports JDK version info.")
    public ResponseEntity<Map<String, Object>> demonstrateIssue2() {
        return ResponseEntity.ok(fakeService.demonstrateIssue2VirtualThreads());
    }

    @GetMapping("/issue-3-static-id")
    @Operation(summary = "Demonstrate Issue 3: Static ID Fragility", description = "Demonstrates static 'id': '1' in tools/list vs dynamic UUID in callTool.")
    public ResponseEntity<Map<String, Object>> demonstrateIssue3() {
        return ResponseEntity.ok(fakeService.demonstrateIssue3StaticId());
    }

    @GetMapping("/issue-4-swallowed-exceptions")
    @Operation(summary = "Demonstrate Issue 4: Swallowed Exception Nuances", description = "Demonstrates broad catch (Exception e) swallowing timeouts and connection errors.")
    public ResponseEntity<Map<String, Object>> demonstrateIssue4() {
        return ResponseEntity.ok(fakeService.demonstrateIssue4BroadCatch());
    }
}
