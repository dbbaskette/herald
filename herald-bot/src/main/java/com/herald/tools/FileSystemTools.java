package com.herald.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File system tools for reading, writing, and listing files.
 * Stub implementation until spring-ai-agent-utils provides the canonical version.
 */
@Component
public class FileSystemTools {

    @Tool(description = "Read the contents of a file at the given path.")
    public String file_read(
            @ToolParam(description = "Absolute path to the file to read") String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Write content to a file at the given path, creating parent directories if needed.")
    public String file_write(
            @ToolParam(description = "Absolute path to the file to write") String path,
            @ToolParam(description = "Content to write to the file") String content) {
        try {
            Path target = Path.of(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return "Written to " + path;
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "List files and directories at the given path.")
    public String file_list(
            @ToolParam(description = "Absolute path to the directory to list") String path) {
        try (Stream<Path> entries = Files.list(Path.of(path))) {
            return entries.map(p -> (Files.isDirectory(p) ? "[dir]  " : "[file] ") + p.getFileName())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
