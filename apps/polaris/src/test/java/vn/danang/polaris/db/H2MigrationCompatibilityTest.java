package vn.danang.polaris.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import vn.danang.polaris.order.repository.OrderRepository;

/**
 * H2 is the local-development default datasource ({@code application.yml}), while tests and production run on
 * PostgreSQL. Every Flyway migration must therefore apply on plain H2 too (AGENTS.md, dev-prod parity), and the
 * native order-number query must run on both.
 */
class H2MigrationCompatibilityTest {

    private static final String URL = "jdbc:h2:mem:h2-migration-compat;DB_CLOSE_DELAY=-1";

    @Test
    @DisplayName("all migrations apply on H2 and the order-number sequence query works there")
    void migrationsApplyOnH2_andOrderNumberSequenceIsUsable() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // Sub-versions such as V15_1 are allowed (TR-X4), so compare as Flyway versions, not integers
        assertThat(org.flywaydb.core.api.MigrationVersion.fromVersion(result.targetSchemaVersion).isAtLeast("13")).isTrue();

        String nextValueSql = OrderRepository.class.getMethod("nextOrderNumberValue")
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            long first = nextValue(statement, nextValueSql);
            long second = nextValue(statement, nextValueSql);
            assertThat(first).isGreaterThanOrEqualTo(10_000_000L);
            assertThat(second).isEqualTo(first + 1);
        }
    }

    private static long nextValue(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
