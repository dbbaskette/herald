package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Advisor that reads {@code CONTEXT.md} from disk on each turn and injects its content
 * into the system prompt. This provides a human-editable standing brief that the model
 * always has access to. Changes to the file are reflected immediately on the next turn.
 *
 * <p>Runs after {@link DateTimePromptAdvisor} but before {@link MemoryBlockAdvisor},
 * so context assembly order is: system prompt → CONTEXT.md → memory block.</p>
 */
class ContextMdAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextMdAdvisor.class);

    private final Path contextFilePath;

    ContextMdAdvisor(Path contextFilePath) {
        this.contextFilePath = contextFilePath;
    }

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String content = readContextFile();
            if (!content.isEmpty()) {
                String delimited = "\n\n<context>\n" + content + "\n</context>";
                request = request.mutate()
                        .prompt(request.prompt().augmentSystemMessage(
                                existing -> new SystemMessage(existing.getText() + delimited)))
                        .build();
            }
            return chain.nextCall(request);
        } finally {
            INJECTED.remove();
        }
    }

    @Override
    public String getName() {
        return "ContextMdAdvisor";
    }

    @Override
    public int getOrder() {
        // Between DateTimePromptAdvisor (+50) and MemoryBlockAdvisor (+100)
        return Ordered.HIGHEST_PRECEDENCE + 75;
    }

    /**
     * Reads the CONTEXT.md file from disk. If the file does not exist or cannot be read,
     * returns an empty string and logs a debug/warn message.
     */
    String readContextFile() {
        if (!Files.exists(contextFilePath)) {
            log.debug("CONTEXT.md not found at {}", contextFilePath);
            return "";
        }
        try {
            String content = Files.readString(contextFilePath, StandardCharsets.UTF_8).trim();
            return content;
        } catch (IOException e) {
            log.warn("Failed to read CONTEXT.md at {}: {}", contextFilePath, e.getMessage());
            return "";
        }
    }

    /**
     * Creates the CONTEXT.md file with a starter template if it does not already exist.
     * Called once at startup to ensure the file is available for the user to edit.
     */
    void ensureTemplateExists(String templateContent) {
        if (Files.exists(contextFilePath)) {
            return;
        }
        try {
            Files.createDirectories(contextFilePath.getParent());
            Files.writeString(contextFilePath, templateContent, StandardCharsets.UTF_8);
            log.info("Created starter CONTEXT.md at {}", contextFilePath);
        } catch (IOException e) {
            log.warn("Failed to create starter CONTEXT.md at {}: {}", contextFilePath, e.getMessage());
        }
    }

    /**
     * Updates the Obsidian Configuration section in CONTEXT.md from the configured vault path.
     * Derives the vault name and Herald folder from the path. Called at startup.
     */
    void updateObsidianConfig(String vaultPath) {
        if (vaultPath == null || vaultPath.isBlank() || !Files.exists(contextFilePath)) {
            return;
        }

        // Derive vault name and parent from the path
        // e.g. "/Users/.../Documents/Herald-Memory" → vault parent = "Documents" parent, folder = "Herald-Memory"
        // Only expand ~ at the start of the path (not iCloud~md~obsidian tildes)
        String expanded = vaultPath.startsWith("~/")
                ? System.getProperty("user.home") + vaultPath.substring(1)
                : vaultPath;
        Path resolved = Path.of(expanded);
        String folderName = resolved.getFileName().toString();
        String parentName = resolved.getParent() != null ? resolved.getParent().getFileName().toString() : folderName;

        // Check if Herald-Memory is a subfolder (common case) or a standalone vault
        // If the folder itself IS the vault, use it directly
        String vaultName;
        String heraldFolder;
        // Try to detect: is this a vault root or a subfolder?
        // If .obsidian dir exists inside the path, it's a vault root
        if (Files.isDirectory(resolved.resolve(".obsidian"))) {
            vaultName = folderName;
            heraldFolder = "";
        } else {
            // It's a subfolder inside a parent vault
            vaultName = parentName;
            heraldFolder = folderName;
        }

        String section = buildObsidianSection(vaultName, heraldFolder, resolved.toString());

        try {
            String content = Files.readString(contextFilePath, StandardCharsets.UTF_8);
            String marker = "## Obsidian Configuration";

            if (content.contains(marker)) {
                // Replace existing section (up to next ## or end of file)
                content = content.replaceAll(
                        "## Obsidian Configuration\\n[\\s\\S]*?(?=\\n## |\\Z)",
                        section);
            } else {
                // Insert before ## Notes or at end
                if (content.contains("## Notes")) {
                    content = content.replace("## Notes", section + "\n## Notes");
                } else {
                    content = content + "\n" + section;
                }
            }

            Files.writeString(contextFilePath, content, StandardCharsets.UTF_8);
            log.info("Updated Obsidian config in CONTEXT.md: vault={}, folder={}", vaultName, heraldFolder);
        } catch (IOException e) {
            log.warn("Failed to update Obsidian config in CONTEXT.md: {}", e.getMessage());
        }
    }

    private String buildObsidianSection(String vaultName, String heraldFolder, String fullPath) {
        var sb = new StringBuilder();
        sb.append("## Obsidian Configuration\n\n");
        sb.append("- **Vault name:** ").append(vaultName).append("\n");
        if (!heraldFolder.isEmpty()) {
            sb.append("- **Herald folder:** ").append(heraldFolder)
                    .append(" (all Herald notes go under this subfolder)\n");
            sb.append("- **Vault path:** ").append(Path.of(fullPath).getParent()).append("\n");
            sb.append("- When using the obsidian CLI, always use `vault=\"").append(vaultName)
                    .append("\"` and prefix paths with `").append(heraldFolder).append("/`\n");
            sb.append("- Example: `obsidian search vault=\"").append(vaultName)
                    .append("\" query=\"...\" path=\"").append(heraldFolder).append("/Chat-Sessions\"`\n\n");
        } else {
            sb.append("- **Vault path:** ").append(fullPath).append("\n");
            sb.append("- When using the obsidian CLI, always use `vault=\"").append(vaultName).append("\"`\n");
            sb.append("- Example: `obsidian search vault=\"").append(vaultName)
                    .append("\" query=\"...\" path=\"Chat-Sessions\"`\n\n");
        }
        return sb.toString();
    }
}
