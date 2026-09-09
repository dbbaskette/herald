package com.herald.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Confined, bounded, optimistic file editing. Trash tokens contain no caller-controlled paths. */
final class FileMemoryService {
    static final int MAX_BYTES = 256 * 1024;
    final Path root;
    FileMemoryService(String raw) {
        if (raw == null || raw.isBlank()) raw = "~/.herald/memories";
        Path configured = (raw.startsWith("~/") ? Path.of(System.getProperty("user.home"), raw.substring(2)) : Path.of(raw)).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(configured)) throw fail(400, "Symbolic memory roots are not supported");
        Path existing = configured;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        try { root = existing == null ? configured : existing.toRealPath().resolve(existing.relativize(configured)); }
        catch (IOException ex) { throw fail(400, "Cannot resolve memory root"); }
    }
    Path safe(String relative, boolean trash) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("\\")) throw fail(400, "Use a relative Markdown path");
        Path rel;
        try { rel = Path.of(relative); } catch (InvalidPathException ex) { throw fail(400, "Invalid memory path"); }
        if (rel.isAbsolute()) throw fail(400, "Absolute paths are not allowed");
        for (Path part : rel) if (part.toString().equals("..") || part.toString().equals(".")) throw fail(400, "Traversal is not allowed");
        if (!trash) for (Path part : rel) if (part.toString().startsWith(".")) throw fail(400, "Hidden memory paths are not supported");
        if (!trash && (relative.startsWith(".") || !relative.endsWith(".md"))) throw fail(400, "Use a visible Markdown path");
        Path result = root.resolve(rel).normalize();
        if (!result.startsWith(root)) throw fail(400, "Path escapes memory directory");
        for (Path p = result; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw fail(400, "Symbolic links are not supported");
        return result;
    }
    byte[] bytes(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw fail(404, "Memory file not found");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw fail(413, "Memory exceeds 256 KiB; edit it locally. No partial text was loaded.");
            return bytes;
        }
    }
    static String version(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    FileMemoryController.MemoryPageContent read(String path) throws IOException {
        Path file = safe(path, false); byte[] data = bytes(file);
        return new FileMemoryController.MemoryPageContent(path, new String(data, StandardCharsets.UTF_8), data.length, version(data));
    }
    synchronized FileMemoryController.MemoryPageContent save(String path, String content, String expected) throws IOException {
        Path file = safe(path, false); checkVersion(file, expected);
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw fail(413, "Text must be at most 256 KiB");
        Path tmp = Files.createTempFile(file.getParent(), ".memory-edit-", ".tmp");
        try { Files.writeString(tmp, content, StandardCharsets.UTF_8); checkVersion(safe(path, false), expected); Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
        return read(path);
    }
    void checkVersion(Path file, String expected) throws IOException {
        if (expected == null || !expected.equals(version(bytes(file)))) throw fail(409, "Memory changed on disk. Reload and reconcile your draft before saving.");
    }
    synchronized Trash trash(String path, String expected) throws IOException {
        Path source = safe(path, false); checkVersion(source, expected);
        String token = UUID.randomUUID().toString();
        Path dir = safe(".memory-trash/" + token, true); Files.createDirectories(dir);
        Files.writeString(dir.resolve("original-path"), path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(source, dir.resolve("memory.md"), StandardCopyOption.ATOMIC_MOVE);
        return new Trash(token, ".memory-trash/" + token + "/memory.md", path);
    }
    synchronized FileMemoryController.MemoryPageContent restore(String token) throws IOException {
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw fail(400, "Invalid recovery token");
        Path manifest = safe(".memory-trash/" + token + "/original-path", true);
        String path = new String(bytes(manifest), StandardCharsets.UTF_8);
        Path destination = safe(path, false), source = safe(".memory-trash/" + token + "/memory.md", true);
        bytes(source);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw fail(409, "Restore would overwrite an existing memory. Rename it first.");
        Files.createDirectories(destination.getParent()); safe(path, false);
        // No REPLACE_EXISTING: never overwrite a note created since deletion.
        Files.move(source, destination);
        Files.deleteIfExists(manifest); Files.deleteIfExists(manifest.getParent());
        return read(path);
    }
    List<Path> pages() throws IOException {
        if (!Files.exists(root)) return List.of();
        safe("MEMORY.md", false);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> { for (Path part : root.relativize(p)) if (part.toString().startsWith(".")) return false; return true; })
                .filter(p -> !p.getFileName().toString().equals("log.md"))
                .sorted().toList();
        }
    }
    static ResponseStatusException fail(int code, String message) { return new ResponseStatusException(HttpStatus.valueOf(code), message); }
    record Trash(String token, String trashPath, String originalPath) {}
}
