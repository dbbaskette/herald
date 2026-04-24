package com.herald.telegram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link CommandHandler} — TelegramPollerTest can't use
 * Mockito.mock() on the real class once the type graph pulls in enough
 * record nests (BudgetPolicy in particular). This stub supplies just the
 * surface the poller calls: {@code handle(String) -> boolean}.
 */
class CommandHandlerStub extends CommandHandler {

    private final List<String> calls = new ArrayList<>();
    private final Map<String, Boolean> programmedReturns = new HashMap<>();
    private boolean defaultReturn = false;

    CommandHandlerStub() {
        super(null, null, null, null, null, List.of(),
                null, null, 0, null,
                java.util.Optional.empty(), null,
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    @Override
    boolean handle(String text) {
        calls.add(text);
        return programmedReturns.getOrDefault(text, defaultReturn);
    }

    /** Program a return value for a specific input. Mirrors Mockito's when(...) API. */
    CommandHandlerStub when(String input, boolean result) {
        programmedReturns.put(input, result);
        return this;
    }

    void setDefaultReturn(boolean value) {
        this.defaultReturn = value;
    }

    List<String> calls() {
        return List.copyOf(calls);
    }
}
