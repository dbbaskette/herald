package com.herald.doctor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorTest {

    private static class StubCheck implements HealthCheck {
        private final String name;
        private final Result result;
        StubCheck(String name, Result result) { this.name = name; this.result = result; }
        @Override public String name() { return name; }
        @Override public Result run() { return result; }
    }

    private static class ThrowingCheck implements HealthCheck {
        @Override public String name() { return "explodes"; }
        @Override public Result run() { throw new RuntimeException("kaboom"); }
    }

    private int runDoctor(List<HealthCheck> checks, Doctor.OutputMode mode, ByteArrayOutputStream out) {
        Doctor doctor = new Doctor(checks, new PrintStream(out, true, StandardCharsets.UTF_8));
        return doctor.run(mode);
    }

    @Test
    void allOkExitsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = runDoctor(List.of(
                new StubCheck("a", HealthCheck.Result.ok("fine")),
                new StubCheck("b", HealthCheck.Result.ok("also fine"))),
                Doctor.OutputMode.HUMAN, out);

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\u2713")
                .contains("Summary: 2 ok, 0 warnings, 0 failures");
    }

    @Test
    void anyWarnExitsOne() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = runDoctor(List.of(
                new StubCheck("a", HealthCheck.Result.ok("fine")),
                new StubCheck("b", HealthCheck.Result.warn("heads up", "fix me"))),
                Doctor.OutputMode.HUMAN, out);

        assertThat(code).isOne();
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("Fix: fix me");
        assertThat(report).contains("Summary: 1 ok, 1 warning, 0 failures");
    }

    @Test
    void anyFailExitsTwo() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = runDoctor(List.of(
                new StubCheck("a", HealthCheck.Result.warn("heads up")),
                new StubCheck("b", HealthCheck.Result.fail("broken"))),
                Doctor.OutputMode.HUMAN, out);

        assertThat(code).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Summary: 0 ok, 1 warning, 1 failure");
    }

    @Test
    void throwingCheckIsClassifiedAsFailure() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = runDoctor(List.of(new ThrowingCheck()), Doctor.OutputMode.HUMAN, out);
        assertThat(code).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("check threw RuntimeException: kaboom");
    }

    @Test
    void quietModeSuppressesOkLines() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runDoctor(List.of(
                new StubCheck("happy", HealthCheck.Result.ok("fine")),
                new StubCheck("noisy", HealthCheck.Result.warn("listen up"))),
                Doctor.OutputMode.QUIET, out);

        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report).doesNotContain("happy");
        assertThat(report).contains("noisy");
    }

    @Test
    void jsonModeEmitsValidJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runDoctor(List.of(
                new StubCheck("a", HealthCheck.Result.ok("fine")),
                new StubCheck("b", HealthCheck.Result.fail("broken", "reinstall"))),
                Doctor.OutputMode.JSON, out);

        String report = out.toString(StandardCharsets.UTF_8);
        // Just enough shape-checking to confirm the schema — not a full JSON parser test.
        assertThat(report)
                .startsWith("{")
                .contains("\"timestamp\":")
                .contains("\"checks\": [")
                .contains("\"name\": \"a\"")
                .contains("\"status\": \"OK\"")
                .contains("\"name\": \"b\"")
                .contains("\"status\": \"FAIL\"")
                .contains("\"fix\": \"reinstall\"")
                .endsWith("}\n");
    }

    @Test
    void parseModeDefaultsToHuman() {
        assertThat(Doctor.parseMode(new String[0])).isEqualTo(Doctor.OutputMode.HUMAN);
    }

    @Test
    void parseModeRecognizesJson() {
        assertThat(Doctor.parseMode(new String[]{"--json"})).isEqualTo(Doctor.OutputMode.JSON);
    }

    @Test
    void parseModeRecognizesQuietFlags() {
        assertThat(Doctor.parseMode(new String[]{"--quiet"})).isEqualTo(Doctor.OutputMode.QUIET);
        assertThat(Doctor.parseMode(new String[]{"-q"})).isEqualTo(Doctor.OutputMode.QUIET);
    }

    @Test
    void defaultChecksExposesFullBattery() {
        List<HealthCheck> checks = Doctor.defaultChecks();
        assertThat(checks).extracting(HealthCheck::name)
                .contains("Java runtime", "Anthropic API key", "Telegram bot token",
                        "SQLite database", "Memory directory", "Skills directory",
                        "Google Workspace CLI", "Reminders CLI (macOS)",
                        "herald-bot port 8081", "herald-ui port 8080");
    }
}
