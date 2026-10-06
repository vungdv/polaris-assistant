/**
 * Published integration event contracts (EM-002 §4, programme Δ4/Δ5).
 *
 * <p>Payload records, CloudEvents {@code type}/{@code source} values and logical destinations only:
 * no behaviour, no Spring and no persistence, so stateless apps can depend on it without a datasource.
 * Contracts are additive-only within a {@code .vN} type (TR-X6).
 */
package vn.danang.polaris.events;
