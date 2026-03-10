package com.herald.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards non-API, non-static requests to index.html so that
 * Vue Router's HTML5 history mode works when served by Spring Boot.
 */
@Controller
public class SpaForwardingController {

    @GetMapping(value = {"/", "/skills", "/memory", "/cron", "/history"})
    public String forward() {
        return "forward:/index.html";
    }
}
