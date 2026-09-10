package com.herald.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/memory/files")
class FileMemoryController {
    private final FileMemoryService files;
    @Value("${herald.obsidian.vault-path:${HERALD_OBSIDIAN_VAULT_PATH:}}")
    private String obsidianVault = "";
    FileMemoryController(@Value("${herald.ui.memories-path:${HERALD_MEMORIES_DIR:~/.herald/memories}}") String path) { files = new FileMemoryService(path); }
    Map<String,List<MemoryPage>> listGroupedByType() { return list("", ""); }
    @GetMapping
    Map<String,List<MemoryPage>> list(@RequestParam(defaultValue="") String query, @RequestParam(defaultValue="") String tag) {
        Map<String,List<MemoryPage>> groups = new LinkedHashMap<>();
        for (String type : List.of("index","user","feedback","project","reference","concept","entity","source","unknown")) groups.put(type,new ArrayList<>());
        try {
            for (Path file : files.pages()) {
                String path = files.root.relativize(file).toString().replace('\\','/');
                String body = ""; boolean oversized = Files.size(file) > FileMemoryService.MAX_BYTES;
                if (!oversized) body = files.read(path).content();
                Map<String,String> fm = fields(body);
                List<String> tags = tags(body, fm.get("tags"));
                if (!tag.isBlank() && !tags.contains(tag.replaceFirst("^#", "").toLowerCase(Locale.ROOT))) continue;
                if (!query.isBlank() && !(path + "\n" + body).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
                String type = path.equals("MEMORY.md") ? "index" : fm.getOrDefault("type","unknown").toLowerCase(Locale.ROOT);
                if (!groups.containsKey(type)) type = "unknown";
                var attribution = metadata(".memory-attribution", path);
                if (oversized || !Objects.equals(attribution.getProperty("version"), FileMemoryService.version(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) attribution.clear();
                String conversation = fm.getOrDefault("conversation_id", fm.getOrDefault("conversationId", attribution.getProperty("conversationId")));
                groups.get(type).add(new MemoryPage(path, fm.get("name"), fm.get("description"), type, Files.size(file), Files.getLastModifiedTime(file).toInstant().toString(), tags, conversation, fm.getOrDefault("created",fm.getOrDefault("date",attribution.getProperty("createdAt"))), oversized));
            }
            return groups;
        } catch(IOException ex) { throw FileMemoryService.fail(500,"Could not list memory files"); }
    }
    @GetMapping("/capabilities")
    Map<String,Boolean> capabilities() { return Map.of("obsidian", !obsidianVault.isBlank()); }
    @GetMapping("/context")
    Map<String,Object> context(@RequestParam String conversationId) {
        Properties props = metadata(".memory-context",conversationId);
        boolean active = false;
        try {
            var observed = java.time.Instant.parse(props.getProperty("observedAt"));
            active = props.getProperty("active", "false").equals("true") && observed.isAfter(java.time.Instant.now().minusSeconds(60)) && !observed.isAfter(java.time.Instant.now().plusSeconds(5));
        } catch(Exception ignored) {}
        List<String> paths = new ArrayList<>();
        if(active) for(String key:props.stringPropertyNames()) if(key.startsWith("path.")) {
            try { String path=props.getProperty(key); if(Files.isRegularFile(files.safe(path,false))) paths.add(path); } catch(Exception ignored) {}
        }
        return Map.of("active",active,"paths",paths,"observedAt",props.getProperty("observedAt",""),"description","Only actual reads in the executing turn are marked. Tool text is not retained after the turn.");
    }
    private Properties metadata(String directory,String key) {
        Properties props=new Properties();
        try {
            String hash=FileMemoryService.version(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path path=files.safe(directory+"/"+hash+".properties",true);
            try(var in=new java.io.ByteArrayInputStream(files.bytes(path))){props.load(in);}
        } catch(Exception ignored) {}
        return props;
    }
    @GetMapping("/content")
    ResponseEntity<MemoryPageContent> content(@RequestParam("path") String path) {
        try { return ResponseEntity.ok(files.read(path)); }
        catch(ResponseStatusException ex) { return ResponseEntity.status(ex.getStatusCode()).build(); }
        catch(IOException ex) { throw FileMemoryService.fail(500,"Could not read memory file"); }
    }
    @PutMapping("/content")
    MemoryPageContent save(@RequestBody Edit edit) throws IOException { return files.save(edit.path(),edit.content(),edit.version()); }
    @DeleteMapping("/content")
    FileMemoryService.Trash trash(@RequestParam String path,@RequestParam String version) throws IOException { return files.trash(path,version); }
    @PostMapping("/restore")
    MemoryPageContent restore(@RequestBody Restore restore) throws IOException { return files.restore(restore.token()); }
    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String,String>> ioError() { return ResponseEntity.internalServerError().body(Map.of("message","Memory operation failed; your draft is preserved.")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> clientError(ResponseStatusException ex) { return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(ex.getReason(),"Memory operation failed"))); }
    @GetMapping("/health")
    MemoryHealth health() {
        try {
            var pages = files.pages();
            String latest = null; long newest = Long.MIN_VALUE;
            for(Path p:pages) { long time=Files.getLastModifiedTime(p).toMillis(); if(time>newest) {newest=time;latest=Files.getLastModifiedTime(p).toInstant().toString();} }
            return new MemoryHealth(files.root.toString(),Files.isDirectory(files.root), (int)pages.stream().filter(p -> !List.of("MEMORY.md","hot.md").contains(p.getFileName().toString())).count(), latest, pages.contains(files.root.resolve("MEMORY.md")));
        } catch(Exception ex) { return new MemoryHealth(files.root.toString(),false,0,null,false); }
    }
    static boolean isMarkdownPage(Path p) { return p.toString().endsWith(".md"); }
    static Frontmatter parseFrontmatter(Path file) throws IOException {
        String text; try(var in=Files.newInputStream(file)) {text=new String(in.readNBytes(FileMemoryService.MAX_BYTES),java.nio.charset.StandardCharsets.UTF_8);}
        var fm=fields(text);return new Frontmatter(fm.get("name"),fm.get("description"),fm.get("type"));
    }
    static Map<String,String> fields(String text) {
        Map<String,String> fm=new LinkedHashMap<>(); String[] lines=text.split("\\R");
        if(lines.length==0||!lines[0].trim().equals("---"))return fm;
        String lastKey = "";
        for(int i=1;i<lines.length&&!lines[i].trim().equals("---");i++) {
            if (lastKey.equals("tags") && lines[i].trim().startsWith("- ")) { fm.merge("tags", lines[i].trim().substring(2), (a,b) -> a + "," + b); continue; }
            int colon=lines[i].indexOf(':');if(colon<1)continue;lastKey=lines[i].substring(0,colon).trim();String value=lines[i].substring(colon+1).trim();if(value.length()>1&&((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);fm.put(lines[i].substring(0,colon).trim(),value);}
        return fm;
    }
    static List<String> tags(String body,String field) {
        Set<String> tags=new TreeSet<>();
        if(field!=null) for(String t:field.replaceAll("[\\[\\]\"']", "").split("[,\\s]+"))if(!t.isBlank())tags.add(t.replaceFirst("^#", "").toLowerCase(Locale.ROOT));
        var matcher=java.util.regex.Pattern.compile("(?<![\\w/])#([\\p{L}\\p{N}_/-]+)").matcher(body);
        while(matcher.find())tags.add(matcher.group(1).toLowerCase(Locale.ROOT));return List.copyOf(tags);
    }
    record Edit(String path,String content,String version) {}
    record Restore(String token) {}
    record MemoryPage(String path,String name,String description,String type,long size,String lastModified,List<String> tags,String conversationId,String createdAt,boolean oversized) {}
    record MemoryPageContent(String path,String content,long size,String version) {}
    record Frontmatter(String name,String description,String type) {}
    record MemoryHealth(String path,boolean exists,int noteCount,String lastWrite,boolean hasIndex) {}
}
