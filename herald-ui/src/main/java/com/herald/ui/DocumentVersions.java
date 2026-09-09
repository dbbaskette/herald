package com.herald.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/** Content versions shared by the console's file editors. */
final class DocumentVersions {
    private DocumentVersions() {}
    static String etag(String content) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8))) + "\"";
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ResponseEntity<Map<String, Object>> check(String expected, String content) {
        String version = etag(content);
        if (expected != null && expected.equals(version)) return null;
        return ResponseEntity.status(expected == null ? 428 : 412).eTag(version)
                .body(Map.of("error", expected == null ? "Read this document and send its ETag in If-Match before modifying it."
                        : "This document changed externally. Review the latest version before saving.",
                        "content", content, "version", version));
    }
}
