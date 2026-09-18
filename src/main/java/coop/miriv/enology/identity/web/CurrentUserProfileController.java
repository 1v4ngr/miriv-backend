package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.AccountSummaryResponse;
import coop.miriv.enology.identity.dto.CenterMemberResponse;
import coop.miriv.enology.identity.dto.CenterOption;
import coop.miriv.enology.identity.dto.CurrentUserProfileResponse;
import coop.miriv.enology.identity.dto.UpdateProfileRequest;
import coop.miriv.enology.identity.service.CurrentUserProfileService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class CurrentUserProfileController {

    private final CurrentUserProfileService service;

    public CurrentUserProfileController(CurrentUserProfileService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public AccountSummaryResponse me() {
        return service.getAccountSummary();
    }

    @GetMapping("/profile")
    public CurrentUserProfileResponse currentProfile() {
        return service.getCurrentProfile();
    }

    @PatchMapping("/me")
    public CurrentUserProfileResponse updateCurrentProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return service.updateCurrentProfile(request);
    }

    @GetMapping("/centers")
    public List<CenterOption> centers() {
        return service.listCenters();
    }

    @GetMapping("/center-members")
    public List<CenterMemberResponse> centerMembers() {
        return service.listCenterMembers();
    }
}
