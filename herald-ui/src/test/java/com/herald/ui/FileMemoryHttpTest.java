package com.herald.ui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class FileMemoryHttpTest {
    @TempDir Path root;
    @Test void editsDeletesAndRestoresThroughHttpWithConflictProtection() throws Exception {
        Files.writeString(root.resolve("a.md"),"old");
        var mvc=MockMvcBuilders.standaloneSetup(new FileMemoryController(root.toString())).build();
        var mapper=new tools.jackson.databind.ObjectMapper();
        var read=mvc.perform(get("/api/memory/files/content").param("path","a.md")).andExpect(status().isOk()).andReturn();
        String version=mapper.readTree(read.getResponse().getContentAsString()).get("version").asText();
        var saved=mvc.perform(put("/api/memory/files/content").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("path","a.md","content","new","version",version))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("new")).andReturn();
        mvc.perform(put("/api/memory/files/content").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("path","a.md","content","stale","version",version)))).andExpect(status().isConflict());
        version=mapper.readTree(saved.getResponse().getContentAsString()).get("version").asText();
        var deleted=mvc.perform(delete("/api/memory/files/content").param("path","a.md").param("version",version)).andExpect(status().isOk()).andExpect(jsonPath("$.trashPath").exists()).andReturn();
        String token=mapper.readTree(deleted.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/memory/files/restore").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("token",token)))).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("new"));
        mvc.perform(get("/api/memory/files/content").param("path","../a.md")).andExpect(status().isBadRequest());
    }
}
