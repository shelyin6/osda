package com.osda.web;

import com.osda.lineage.LineageResult;
import com.osda.lineage.LineageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    /**
     * @param direction UPSTREAM (who writes this table and what they read) or DOWNSTREAM
     *                  (who reads this table and what they write)
     */
    @GetMapping
    public LineageResult lineage(
            @RequestParam("object") String object,
            @RequestParam(defaultValue = "UPSTREAM") String direction,
            @RequestParam(required = false) Integer maxDepth
    ) {
        return lineageService.trace(object, direction, maxDepth);
    }

    @GetMapping("/objects")
    public java.util.Set<String> objects() {
        return lineageService.knownObjects();
    }
}
