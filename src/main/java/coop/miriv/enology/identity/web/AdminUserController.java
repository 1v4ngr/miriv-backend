package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.AdminUserResponse;
import coop.miriv.enology.identity.dto.CreateAdminUserRequest;
import coop.miriv.enology.identity.dto.UpdateUserCentersRequest;
import coop.miriv.enology.identity.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service = service; }
    @GetMapping public List<AdminUserResponse> list() { return service.list(); }
    @PostMapping public AdminUserResponse create(@Valid @RequestBody CreateAdminUserRequest request) { return service.create(request); }
    @PutMapping("/{username}/centers") public AdminUserResponse updateCenters(@PathVariable String username,
            @Valid @RequestBody UpdateUserCentersRequest request) { return service.updateCenters(username, request.centerCodes()); }
}