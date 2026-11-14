package kr.seoulhousing.monitor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

@Controller
class SpaRouteController {
    @GetMapping({"/", "/announcements", "/preferences", "/programs", "/admin", "/admin/work-items", "/admin/imports", "/admin/import", "/admin/import/{extractionId}"})
    String app() {
        return "forward:/index.html";
    }

    @GetMapping("/admin.html")
    String legacyAdmin() {
        return "redirect:/admin";
    }

    @GetMapping("/admin-data.html")
    String legacyWorkItems() {
        return "redirect:/admin/work-items";
    }

    @GetMapping("/admin-imports.html")
    String legacyImports() {
        return "redirect:/admin/imports";
    }

    @GetMapping("/admin-import.html")
    String legacyImport(@RequestParam Optional<String> id) {
        return id.filter(value -> !value.isBlank())
                .map(value -> "redirect:/admin/import/" + UriUtils.encodePathSegment(value, StandardCharsets.UTF_8))
                .orElse("redirect:/admin/import");
    }
}
