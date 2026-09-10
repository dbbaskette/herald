package com.herald.agent;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Cross-process evidence of actual memory use, scoped to the executing model turn.
 * Tool results are not retained by OneShotMemoryAdvisor, so completion clears active status. */
final class MemoryContextEvidence {
    final Path root;
    final String conversation;
    final String invocation = UUID.randomUUID().toString();
    final Set<String> paths = new LinkedHashSet<>();
    MemoryContextEvidence(Path root, String conversation) { Path resolved; try { resolved=root.toRealPath(); } catch(Exception ex) {resolved=root.toAbsolutePath().normalize();} this.root=resolved; this.conversation=conversation; }
    synchronized void record(String raw) {
        String path = relative(raw);
        if(path != null) { paths.add(path); publish(true); }
    }
    String relative(String raw) {
        if(raw==null || raw.isBlank())return null;
        try {
            Path file=Path.of(raw); if(!file.isAbsolute())file=root.resolve(file);
            file=file.toAbsolutePath().normalize();Path base=root.toAbsolutePath().normalize();
            if(!file.startsWith(base)||!Files.isRegularFile(file)||!file.toString().endsWith(".md"))return null;
            for(Path p=file;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))return null;
            String relative=base.relativize(file).toString();if(relative.startsWith("."))return null;
            return relative;
        } catch(Exception ex){return null;}
    }
    synchronized void publish(boolean active) {
        if(conversation==null||conversation.isBlank())return;
        Properties props=new Properties();props.setProperty("conversationId",conversation);props.setProperty("invocation",invocation);
        props.setProperty("active",Boolean.toString(active));props.setProperty("observedAt",Instant.now().toString());
        int i=0;for(String path:paths)props.setProperty("path."+i++,path);
        write(".memory-context",conversation,props);
    }
    void attribution(String raw) {
        String path=relative(raw);if(path==null||conversation==null)return;
        Properties props=new Properties();props.setProperty("conversationId",conversation);props.setProperty("createdAt",Instant.now().toString());props.setProperty("path",path);
        try (var in = Files.newInputStream(root.resolve(path))) {
            byte[] body = in.readNBytes(256 * 1024 + 1);
            if (body.length > 256 * 1024) return;
            props.setProperty("version", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        } catch(Exception ex) { return; }
        write(".memory-attribution",path,props);
    }
    private void write(String directory,String key,Properties props) {
        Path temporary=null;
        try {
            Path dir=root.resolve(directory);
            for(Path p=dir.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))return;
            Files.createDirectories(dir);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            Path file=dir.resolve(hash+".properties"); if(Files.isSymbolicLink(file))return;
            if (directory.equals(".memory-context") && props.getProperty("active").equals("false") && Files.exists(file)) {
                Properties current = new Properties();
                try(var in = Files.newInputStream(file)) { current.load(in); }
                if (!invocation.equals(current.getProperty("invocation"))) return;
            }
            temporary=Files.createTempFile(dir,"evidence-",".tmp");
            try(var out=Files.newOutputStream(temporary)){props.store(out,"Herald actual memory usage");}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){ /* Metadata must never break an agent turn. */ }
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
}
