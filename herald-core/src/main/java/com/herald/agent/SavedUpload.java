package com.herald.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import com.herald.config.HeraldLimits;

/**
 * Helpers for the "save an upload to {@link HeraldLimits#UPLOADS_DIR} and build a
 * human-readable {@code [File received: …]} tag" path shared by Telegram and web
 * chat. Centralizes the filename-sanitization regex that previously drifted
 * between the two callers (issue #354).
 *
 * <p>Each caller still decides on its own whether a given upload is inline
 * (vision-capable image, audio for transcription) or saved-to-disk — that
 * decision is channel-specific (Telegram does inline-extract on documents, web
 * has only multipart bytes). What's shared is the save mechanics + tag format.</p>
 */
public final class SavedUpload {

    /** Replace any run of non-portable chars with a single underscore. */
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]+");

    private SavedUpload() {}

    /** Sanitize a user-supplied filename for use under {@link HeraldLimits#UPLOADS_DIR}. */
    public static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "attachment";
        return SAFE_NAME.matcher(name).replaceAll("_");
    }

    /**
     * Persist {@code bytes} (or whatever streams from {@code in}) under
     * {@code UPLOADS_DIR} as {@code <epochMs>_<safeName>}. Returns the final
     * path. Creates the parent dir on demand.
     */
    public static Path save(InputStream in, String originalName) throws IOException {
        Files.createDirectories(HeraldLimits.UPLOADS_DIR);
        Path target = HeraldLimits.UPLOADS_DIR.resolve(
                System.currentTimeMillis() + "_" + sanitizeFilename(originalName));
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Standard tag the agent sees in its incoming message text whenever the
     * channel saved a non-inlined attachment. Format:
     * {@code [File received: NAME (MIME, BYTES bytes) — saved to PATH]}.
     */
    public static String fileReceivedTag(String name, String mime, long bytes, Path path) {
        return fileReceivedTag(name, mime, bytes, path, null);
    }

    /**
     * Variant with an optional inline {@code hint} (used by Telegram to nudge
     * the agent toward {@code markitdown} for PDFs).
     */
    public static String fileReceivedTag(String name, String mime, long bytes,
                                         Path path, String hint) {
        return String.format("[File received: %s (%s, %d bytes)%s — saved to %s]",
                name, mime, bytes, hint == null ? "" : hint, path);
    }
}
