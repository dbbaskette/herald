package com.herald.meetings;

import com.herald.config.HeraldConfig;
import java.nio.file.*;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Confirms durable summary content, independently of the model's prose response. */
@Component
public class MeetingNoteVerifier {
    private final HeraldConfig config;
    public MeetingNoteVerifier(HeraldConfig config) { this.config=config; }

    public boolean isSaved(MeetingDigest meeting) {
        if (meeting.summaryMarkdown()==null || meeting.summaryMarkdown().isBlank()) return false;
        Set<Path> roots=new LinkedHashSet<>();
        roots.add(expand(config.memoriesDir()));
        if (!config.obsidianVaultPath().isBlank()) roots.add(expand(config.obsidianVaultPath()));
        for (Path root:roots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths=Files.walk(root)) {
                boolean found=paths.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                        .anyMatch(p -> matches(p,meeting));
                if (found) return true;
            } catch (Exception e) { /* An unreadable root is not evidence of success. */ }
        }
        return false;
    }
    private boolean matches(Path path, MeetingDigest meeting) {
        try {
            if (Files.size(path)>4*1024*1024) return false;
            String body=Files.readString(path);
            boolean source=body.lines().anyMatch(line -> line.trim().equals("Source: "+meeting.id()));
            return source && body.contains(meeting.summaryMarkdown().trim());
        } catch (Exception e) { return false; }
    }
    private static Path expand(String raw) {
        return Path.of(raw.startsWith("~/") ? System.getProperty("user.home")+raw.substring(1):raw);
    }
}
