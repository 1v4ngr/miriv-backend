package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.AdminUserResponse;
import coop.miriv.enology.identity.dto.CreateAdminUserRequest;
import coop.miriv.enology.identity.dto.PermissionGrantRequest;
import coop.miriv.enology.identity.dto.RoleAssignmentRequest;
import coop.miriv.enology.identity.dto.UpdateUserCentersRequest;
import coop.miriv.enology.identity.dto.UserAccountResponse;
import coop.miriv.enology.identity.dto.UserAccountStatusRequest;
import coop.miriv.enology.identity.service.AdminUserService;
import coop.miriv.enology.identity.service.UserAccountService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService service;
    private final UserAccountService accounts;

    public AdminUserController(AdminUserService service, UserAccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @GetMapping public List<AdminUserResponse> list() { return service.list(); }
    @PostMapping public AdminUserResponse create(@Valid @RequestBody CreateAdminUserRequest request) { return service.create(request); }
    @PutMapping("/{username}/centers") public AdminUserResponse updateCenters(@PathVariable String username,
            @Valid @RequestBody UpdateUserCentersRequest request) { return service.updateCenters(username, request.centerCodes()); }

    @GetMapping("/accounts") public List<UserAccountResponse> listAccounts() { return accounts.list(); }
    @GetMapping("/accounts/{id}") public UserAccountResponse getAccount(@PathVariable UUID id) { return accounts.get(id); }
    @PostMapping("/accounts/{id}/roles") public UserAccountResponse assignRole(@PathVariable UUID id,
            @Valid @RequestBody RoleAssignmentRequest request) { return accounts.assignRole(id, request); }
    @DeleteMapping("/accounts/{id}/roles/{roleAssignmentId}") public UserAccountResponse revokeRole(@PathVariable UUID id,
            @PathVariable UUID roleAssignmentId) { return accounts.revokeRole(id, roleAssignmentId); }
    @PostMapping("/accounts/{id}/grants") public UserAccountResponse grantPermission(@PathVariable UUID id,
            @Valid @RequestBody PermissionGrantRequest request) { return accounts.grantPermission(id, request); }
    @DeleteMapping("/accounts/{id}/grants/{grantId}") public UserAccountResponse revokePermission(@PathVariable UUID id,
            @PathVariable UUID grantId) { return accounts.revokePermission(id, grantId); }
    @PostMapping("/accounts/{id}/deactivate") public UserAccountResponse deactivate(@PathVariable UUID id,
            @Valid @RequestBody UserAccountStatusRequest request) { return accounts.deactivate(id, request); }
    @PostMapping("/accounts/{id}/reactivate") public UserAccountResponse reactivate(@PathVariable UUID id,
            @Valid @RequestBody UserAccountStatusRequest request) { return accounts.reactivate(id, request); }
}