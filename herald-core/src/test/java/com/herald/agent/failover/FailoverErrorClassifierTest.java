package com.herald.agent.failover;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverErrorClassifierTest {

    @Test
    void classifiesRateLimitFrom429Message() {
        RuntimeException e = new RuntimeException("429 Too Many Requests: rate_limit_exceeded");
        assertThat(FailoverErrorClassifier.classify(e)).isEqualTo(FailoverReason.RATE_LIMIT);
    }

    @Test
    void classifiesRateLimitFromClassName() {
        // Simulate the Spring WebClient subclass name convention.
        class TooManyRequestsException extends RuntimeException {
            TooManyRequestsException() { super("whatever"); }
        }
        assertThat(FailoverErrorClassifier.classify(new TooManyRequestsException()))
                .isEqualTo(FailoverReason.RATE_LIMIT);
    }

    @Test
    void classifiesServerErrorFrom500Message() {
        assertThat(FailoverErrorClassifier.classify(new RuntimeException("500 Internal Server Error")))
                .isEqualTo(FailoverReason.SERVER_ERROR);
        assertThat(FailoverErrorClassifier.classify(new RuntimeException("Bad gateway encountered")))
                .isEqualTo(FailoverReason.SERVER_ERROR);
    }

    @Test
    void classifiesTimeoutFromSocketTimeout() {
        assertThat(FailoverErrorClassifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailoverReason.TIMEOUT);
    }

    @Test
    void classifiesTimeoutFromMessage() {
        assertThat(FailoverErrorClassifier.classify(new RuntimeException("request timed out after 30s")))
                .isEqualTo(FailoverReason.TIMEOUT);
    }

    @Test
    void classifiesUnavailableFromConnectException() {
        assertThat(FailoverErrorClassifier.classify(new ConnectException("connection refused")))
                .isEqualTo(FailoverReason.UNAVAILABLE);
    }

    @Test
    void classifiesUnavailableFromUnknownHost() {
        assertThat(FailoverErrorClassifier.classify(new UnknownHostException("api.anthropic.com")))
                .isEqualTo(FailoverReason.UNAVAILABLE);
    }

    @Test
    void classifiesUnavailableFrom503Message() {
        assertThat(FailoverErrorClassifier.classify(new RuntimeException("503 Service Unavailable")))
                .isEqualTo(FailoverReason.UNAVAILABLE);
    }

    @Test
    void walksCauseChain() {
        RuntimeException wrapped = new RuntimeException("spring wrapper",
                new RuntimeException("inner", new SocketTimeoutException("timed out")));
        assertThat(FailoverErrorClassifier.classify(wrapped)).isEqualTo(FailoverReason.TIMEOUT);
    }

    @Test
    void returnsOtherForUnrecognizedError() {
        assertThat(FailoverErrorClassifier.classify(new IllegalArgumentException("bad input")))
                .isEqualTo(FailoverReason.OTHER);
    }

    @Test
    void handlesNull() {
        assertThat(FailoverErrorClassifier.classify(null)).isEqualTo(FailoverReason.OTHER);
    }

    @Test
    void breaksSelfReferentialCauseChain() {
        // Throwable.initCause refuses self-cause, so simulate the pathological
        // case by overriding getCause() to return this.
        RuntimeException self = new RuntimeException("looped") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(FailoverErrorClassifier.classify(self)).isEqualTo(FailoverReason.OTHER);
    }
}
