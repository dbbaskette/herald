package com.herald.doctor.checks;

import java.util.Map;

import com.herald.doctor.HealthCheck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvVarCheckTest {

    @Test
    void missingFailsWhenSeverityIsFail() {
        EnvVarCheck check = new EnvVarCheck("My var", "HERALD_TEST_MISSING",
                EnvVarCheck.Severity.FAIL, "set it",
                v -> null, Map.of());
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.FAIL);
        assertThat(r.fixHint()).isEqualTo("set it");
    }

    @Test
    void missingWarnsWhenSeverityIsWarn() {
        EnvVarCheck check = new EnvVarCheck("My var", "HERALD_TEST_MISSING",
                EnvVarCheck.Severity.WARN, "set it",
                v -> null, Map.of());
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
    }

    @Test
    void presentPassesAndRedactsValue() {
        EnvVarCheck check = new EnvVarCheck("My var", "HERALD_TEST_KEY",
                EnvVarCheck.Severity.FAIL, "set it",
                v -> null, Map.of("HERALD_TEST_KEY", "sk-abcdefghijklmnop"));
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
        assertThat(r.message()).contains("…mnop");
        assertThat(r.message()).doesNotContain("abcdefghijkl");
    }

    @Test
    void shapeValidatorErrorCausesFailure() {
        EnvVarCheck check = new EnvVarCheck("My var", "HERALD_TEST_KEY",
                EnvVarCheck.Severity.FAIL, "set it",
                v -> v.length() < 10 ? "too short" : null,
                Map.of("HERALD_TEST_KEY", "tiny"));
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.FAIL);
        assertThat(r.message()).contains("too short");
    }

    @Test
    void redactShortSecret() {
        assertThat(EnvVarCheck.redact("abc")).isEqualTo("***c");
        assertThat(EnvVarCheck.redact("")).isEqualTo("empty");
        assertThat(EnvVarCheck.redact("abcdefgh")).isEqualTo("…efgh");
    }

    @Test
    void emptyValueIsTreatedAsMissing() {
        EnvVarCheck check = new EnvVarCheck("My var", "HERALD_TEST_KEY",
                EnvVarCheck.Severity.WARN, "set it",
                v -> null, Map.of("HERALD_TEST_KEY", ""));
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
    }
}
