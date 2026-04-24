package com.herald.agent;

/**
 * A media attachment (image, audio, etc.) accompanying a user message.
 * Used by {@link AgentService#streamChat(String, java.util.List, String)} to
 * pass multimodal content into the chat pipeline — the chat client wraps the
 * bytes as Spring AI {@code Media} on the user message so the model sees the
 * image/audio natively (not as a file path). See issue #320.
 *
 * @param mimeType a standard IANA mime type, e.g. "image/jpeg", "image/png",
 *                 "application/pdf". Determines whether the attachment is
 *                 routed via native vision or falls back to text description.
 * @param data     the raw bytes. Callers are responsible for enforcing any
 *                 upstream size caps before constructing this record.
 * @param label    a short human-readable label (filename, "Photo", etc.) for
 *                 log messages and fallback descriptions.
 */
public record MediaAttachment(String mimeType, byte[] data, String label) {
    public MediaAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required");
        }
        if (data == null) {
            throw new IllegalArgumentException("data is required");
        }
        if (label == null) {
            label = "attachment";
        }
    }

    public long sizeBytes() {
        return data.length;
    }

    public boolean isImage() {
        return mimeType.startsWith("image/");
    }

    public boolean isAudio() {
        return mimeType.startsWith("audio/");
    }
}
