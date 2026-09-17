package coop.miriv.enology.task.web;

import coop.miriv.enology.task.dto.CancelTaskRequest;
import coop.miriv.enology.task.dto.CompleteTaskRequest;
import coop.miriv.enology.task.dto.CreateTaskRequest;
import coop.miriv.enology.task.dto.TaskResponse;
import coop.miriv.enology.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public List<TaskResponse> list() { return service.list(); }

    @GetMapping("/{code}")
    public TaskResponse get(@PathVariable String code) { return service.get(code); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER')")
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) { return service.create(request); }

    @PostMapping("/{code}/start")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'CELLAR_OPERATOR', 'PRODUCTION_MANAGER')")
    public TaskResponse start(@PathVariable String code) { return service.start(code); }

    @PostMapping("/{code}/complete")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'CELLAR_OPERATOR', 'LABORATORY', 'PRODUCTION_MANAGER')")
    public TaskResponse complete(@PathVariable String code, @Valid @RequestBody CompleteTaskRequest request) {
        return service.complete(code, request);
    }

    @PostMapping("/{code}/cancel")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER')")
    public TaskResponse cancel(@PathVariable String code, @Valid @RequestBody CancelTaskRequest request) {
        return service.cancel(code, request.reason());
    }
}
