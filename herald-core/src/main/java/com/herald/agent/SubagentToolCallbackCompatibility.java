package com.herald.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/** Narrow adapter for agent-utils 0.12's defaultTools(List<ToolCallback>) call.
 * Preserve its executor and role filtering while registering callbacks as callbacks.
 * Remove when the upstream executor uses defaultToolCallbacks directly. */
public final class SubagentToolCallbackCompatibility {
    private SubagentToolCallbackCompatibility() {}
    public static ChatClient.Builder wrap(ChatClient.Builder delegate) {
        return (ChatClient.Builder) Proxy.newProxyInstance(ChatClient.Builder.class.getClassLoader(),
                new Class<?>[]{ChatClient.Builder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("defaultTools") && args != null && args.length == 1
                            && args[0] instanceof Object[] objects && objects.length == 1
                            && objects[0] instanceof Collection<?> callbacks
                            && callbacks.stream().allMatch(ToolCallback.class::isInstance)) {
                        delegate.defaultToolCallbacks(callbacks.stream().map(ToolCallback.class::cast).toArray(ToolCallback[]::new));
                        return proxy;
                    }
                    try {
                        Object result = method.invoke(delegate, args);
                        if (result instanceof ChatClient.Builder builder) return builder == delegate ? proxy : wrap(builder);
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
