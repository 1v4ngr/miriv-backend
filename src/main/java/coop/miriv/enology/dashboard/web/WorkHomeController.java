package coop.miriv.enology.dashboard.web;

import coop.miriv.enology.dashboard.dto.WorkHomeResponse;
import coop.miriv.enology.dashboard.service.WorkHomeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-home")
public class WorkHomeController {

    private final WorkHomeService service;

    public WorkHomeController(WorkHomeService service) { this.service = service; }

    @GetMapping
    public WorkHomeResponse get() { return service.get(); }
}
