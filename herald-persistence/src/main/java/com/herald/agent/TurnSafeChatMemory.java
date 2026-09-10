package com.herald.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Repository-backed interim memory until Spring AI's Session API is available.
 * Updates and compaction share this instance's monitor, preventing stale replacement
 * from losing an overlapping append. Message limits are soft at turn boundaries. */
public final class TurnSafeChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final int maxMessages;

    public TurnSafeChatMemory(ChatMemoryRepository repository, int maxMessages) {
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public synchronized void add(String conversationId, List<Message> messages) {
        List<Message> combined = new ArrayList<>(repository.findByConversationId(conversationId));
        combined.addAll(messages);
        replace(conversationId, window(combined, maxMessages));
    }

    @Override
    public synchronized List<Message> get(String conversationId) {
        return repository.findByConversationId(conversationId);
    }

    @Override
    public synchronized void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    public synchronized void replace(String conversationId, List<Message> messages) {
        repository.saveAll(conversationId, messages);
    }

    static boolean isSynthetic(Message message) {
        return Boolean.TRUE.equals(message.getMetadata().get(ContextCompactionAdvisor.SYNTHETIC));
    }

    static boolean isRealUser(Message message) {
        return message instanceof UserMessage && !isSynthetic(message);
    }

    /** Closest real user boundary at or before the requested cut; never evict the latest turn. */
    static int priorUserBoundary(List<Message> messages, int cut) {
        for (int i = Math.min(cut, messages.size() - 1); i >= 0; i--) {
            if (isRealUser(messages.get(i))) return i;
        }
        return 0;
    }

    static List<Message> window(List<Message> messages, int maxMessages) {
        if (messages.size() <= maxMessages) return messages;
        List<Message> prefix = messages.stream()
                .filter(m -> m instanceof SystemMessage || isSynthetic(m)).toList();
        List<Message> dialogue = messages.stream()
                .filter(m -> !(m instanceof SystemMessage) && !isSynthetic(m)).toList();
        int cut = priorUserBoundary(dialogue, Math.max(0, dialogue.size() - Math.max(1, maxMessages - prefix.size())));
        List<Message> result = new ArrayList<>(prefix);
        result.addAll(dialogue.subList(cut, dialogue.size()));
        return result;
    }
}
