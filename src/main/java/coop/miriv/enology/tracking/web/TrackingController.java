package coop.miriv.enology.tracking.web;

import coop.miriv.enology.tracking.dto.TrackingDto.Event;
import coop.miriv.enology.tracking.dto.TrackingDto.OverviewResponse;
import coop.miriv.enology.tracking.dto.TrackingDto.ParameterInfo;
import coop.miriv.enology.tracking.dto.TrackingDto.SeriesResponse;
import coop.miriv.enology.tracking.service.TrackingService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only tracking endpoints; any authenticated user, filtered by center and readable zones. */
@RestController
@RequestMapping("/api/tracking")
public class TrackingController {

    private final TrackingService service;

    public TrackingController(TrackingService service) { this.service = service; }

    @GetMapping("/parameters")
    public List<ParameterInfo> parameters(@RequestParam(defaultValue = "false") boolean all) { return service.parameters(all); }

    @GetMapping("/series")
    public SeriesResponse series(@RequestParam List<String> contents, @RequestParam List<String> parameters,
                                 @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                 @RequestParam(defaultValue = "false") boolean includeAncestors) {
        return service.series(contents, parameters, from, to, includeAncestors);
    }

    @GetMapping("/events")
    public List<Event> events(@RequestParam List<String> contents, @RequestParam(required = false) Instant from,
                              @RequestParam(required = false) Instant to) {
        return service.events(contents, from, to);
    }

    @GetMapping("/overview")
    public OverviewResponse overview(@RequestParam(required = false) List<String> parameters) {
        return service.overview(parameters);
    }
}
