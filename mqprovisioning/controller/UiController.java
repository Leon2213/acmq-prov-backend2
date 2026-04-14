package com.company.mqprovisioning.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {

    @GetMapping("/ui/**")
    public ResponseEntity<Resource> serveUi(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 1. Om det är en fil (t.ex. /ui/static/js/main.js)
        if (path.contains(".")) {
            // Vi mappar /ui/static/... till mappen static/ui/static/...
            // Vi tar bort /ui/ från början för att hitta rätt i classpath
            String resourcePath = path.substring(4); // tar bort "/ui/"
            Resource resource = new ClassPathResource("static/ui/" + resourcePath);

            if (resource.exists() && resource.isReadable()) {
                // Sätt rätt Content-Type baserat på filändelse
                MediaType contentType = path.endsWith(".js") ?
                        MediaType.valueOf("application/javascript") :
                        path.endsWith(".css") ? MediaType.valueOf("text/css") : MediaType.APPLICATION_OCTET_STREAM;

                return ResponseEntity.ok().contentType(contentType).body(resource);
            }
        }

        // 2. Annars (för /ui/ eller React-router vägar), skicka index.html
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/ui/index.html"));
    }
}