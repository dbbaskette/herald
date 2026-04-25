package com.herald.doctor;

/**
 * One diagnostic probe — self-contained, side-effect-free, fast.
 *
 * <p>Checks must not spin up Spring, open external network sessions unless
 * clearly labelled as network checks, or mutate state. They should finish in
 * milliseconds so {@code herald doctor} runs end-to-end in under a second on
 * a typical laptop.</p>
 */
public interface HealthCheck {

    /** Short display name, e.g. {@code "Java runtime"}. */
    String name();

    /** Run the check and return a {@link Result}. Must not throw — wrap failures. */
    Result run();

    /**
     * @param status  pass / warn / fail — maps to glyph + exit code
     * @param message one-line human-readable summary
     * @param fixHint optional remediation suggestion (shown indented under message)
     */
    record Result(Status status, String message, String fixHint) {
        public Result(Status status, String message) {
            this(status, message, null);
        }

        public static Result ok(String message) {
            return new Result(Status.OK, message);
        }

        public static Result warn(String message) {
            return new Result(Status.WARN, message);
        }

        public static Result warn(String message, String fixHint) {
            return new Result(Status.WARN, message, fixHint);
        }

        public static Result fail(String message) {
            return new Result(Status.FAIL, message);
        }

        public static Result fail(String message, String fixHint) {
            return new Result(Status.FAIL, message, fixHint);
        }
    }

    enum Status { OK, WARN, FAIL }
}
