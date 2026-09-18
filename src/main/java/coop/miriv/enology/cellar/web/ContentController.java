package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.ContentResponse;
import coop.miriv.enology.cellar.dto.StateReviewRequest;
import coop.miriv.enology.cellar.service.ContentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents")
public class ContentController {

    private final ContentService service;

    public ContentController(ContentService service) { this.service = service; }

    @GetMapping("/{code}")
    public ContentResponse get(@PathVariable String code) { return service.get(code); }

    @PostMapping("/{code}/state-reviews")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable String code, @Valid @RequestBody StateReviewRequest request) {
        service.review(code, request);
    }
}
