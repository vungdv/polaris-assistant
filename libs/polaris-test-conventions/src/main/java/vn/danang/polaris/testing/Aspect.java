package vn.danang.polaris.testing;

/**
 * The risk an integration test targets: one value per integration row of the scope x aspect matrix in
 * {@code docs/development/testing-guideline.md} §3. Add a value only after changing the guideline.
 */
public enum Aspect {
	/** Query / mapping validity against a real database. */
	PERSISTENCE,
	/** HTTP (or MCP) contract of an endpoint, service mocked. */
	WEB,
	/** Outbound client, messaging or outbox against a real broker, database or stub server. */
	BOUNDARY,
	/** Retry, circuit breaker, timeout or lifecycle behaviour against a deliberately failing collaborator. */
	RESILIENCE,
	/** Proof that a configuration or cross-cutting aspect is applied (auto-configuration, AOP tracing). */
	WIRING
}
