package vn.danang.polaris.common;

/**
 * Architectural overview and domain boundary definitions for the Polaris platform.
 *
 * <p>Polaris platform architecture:
 * <ul>
 *   <li><b>apps/polaris</b>: Unified enterprise service encapsulating Catalog, Order, and MCP gateway bounded contexts.</li>
 *   <li><b>apps/polaris-assistant</b>: Autonomous AI assistant and conversational commerce engine.</li>
 *   <li><b>libs/polaris-common</b>: Shared platform kernel, security filter chains, RFC 7807 problem details, and DB migrations.</li>
 * </ul>
 */
public final class SystemArchitectureOverview {

    private SystemArchitectureOverview() {
        // Utility class
    }
}
