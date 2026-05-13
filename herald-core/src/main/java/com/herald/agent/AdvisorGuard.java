package com.herald.agent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClientResponse;

import reactor.core.publisher.Flux;

/**
 * Thread-local guard that prevents an advisor from re-running its prompt-mutation
 * logic during a nested invocation. Centralizes the boilerplate previously copied
 * across {@link ContextMdAdvisor}, {@link DateTimePromptAdvisor}, and
 * {@link HotMdAdvisor} (one ThreadLocal&lt;Boolean&gt; per advisor).
 *
 * <p>Each advisor identifies itself by a stable string key. Concurrent advisors on
 * the same thread (e.g. ContextMd + Hot + DateTime in the same call) each occupy
 * their own slot in a per-thread {@link Set} and unblock independently.</p>
 *
 * <p>The reactive variant uses {@link Flux#doFinally(java.util.function.Consumer)}
 * to clean up. Note that Reactor schedulers do not propagate ThreadLocal by default,
 * so the cleanup may run on a different thread than the call originated on; the
 * existing advisors already had this behavior, so we preserve it.</p>
 */
public final class AdvisorGuard {

    private static final ThreadLocal<Set<String>> ACTIVE = ThreadLocal.withInitial(HashSet::new);

    private AdvisorGuard() {}

    /**
     * Execute {@code firstCall} on the outermost invocation for {@code key}; on any
     * nested invocation for the same key, execute {@code recursiveCall} instead.
     */
    public static <T> T runCallOnce(String key,
                                    Supplier<T> firstCall,
                                    Supplier<T> recursiveCall) {
        Set<String> active = ACTIVE.get();
        if (!active.add(key)) {
            return recursiveCall.get();
        }
        try {
            return firstCall.get();
        } finally {
            cleanup(key);
        }
    }

    /**
     * Streaming variant. The guard releases on the {@code doFinally} hook attached
     * to the outermost flux.
     */
    public static Flux<ChatClientResponse> runStreamOnce(
            String key,
            Supplier<Flux<ChatClientResponse>> firstCall,
            Supplier<Flux<ChatClientResponse>> recursiveCall) {
        Set<String> active = ACTIVE.get();
        if (!active.add(key)) {
            return recursiveCall.get();
        }
        return firstCall.get().doFinally(signal -> cleanup(key));
    }

    private static void cleanup(String key) {
        Set<String> active = ACTIVE.get();
        active.remove(key);
        if (active.isEmpty()) {
            // Avoid leaking the empty Set on the thread.
            ACTIVE.remove();
        }
    }

    /** Test hook — clears the entire active set on the current thread. */
    static void resetForTesting() {
        ACTIVE.remove();
    }
}
