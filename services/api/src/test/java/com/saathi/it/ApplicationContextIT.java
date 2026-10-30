package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.shared.audit.AuditRecorder;
import com.saathi.shared.config.SaathiProperties;
import com.saathi.shared.error.RequestIdFilter;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The context, started for real, against the schema Flyway built in the Compose container.
 *
 * <p>This is the test that decides whether the stack as configured can come up at all. Unit
 * tests never construct the application, so every wiring mistake — a {@code @ConfigurationProperties}
 * record that does not bind, a bean that is scanned from the wrong package, a readiness group that
 * lists a health indicator Boot 4 does not ship — is invisible until something boots the context.
 */
class ApplicationContextIT extends AbstractIntegrationTest {

    @Autowired
    private SaathiProperties properties;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("the kernel the modules depend on is present in the running context")
    void theSharedKernelIsWired() {
        assertThat(auditRecorder).isNotNull();
        assertThat(clock).isNotNull();
        assertThat(properties.sessionSecret()).isNotBlank();
        assertThat(properties.auditIpKey()).isNotBlank();
    }

    @Test
    @DisplayName("the application is talking to the IT database and to nothing else")
    void theContextPointsAtTheItDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);

        // A test that silently ran against the development database would pass exactly as often
        // and destroy someone's local data. This is the assertion that says it did not.
        assertThat(database).isEqualTo("saathi_it");
    }

    @Test
    @DisplayName("readiness answers 200/UP, which is what the Compose healthcheck reads")
    void readinessIsGreen() {
        var response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode health = body(response);
        assertThat(health.get("status").stringValue()).isEqualTo("UP");
        assertThat(health.get("components").propertyNames())
            .as("§2.2: the gate is the process's own state plus a live connection")
            .containsExactlyInAnyOrder("readinessState", "db");
    }

    @Test
    @DisplayName("the request id filter runs on actuator calls too")
    void theFilterCoversEveryRoute() {
        var response = get("/actuator/health/readiness");

        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(requestId).hasSize(26);
    }
}
