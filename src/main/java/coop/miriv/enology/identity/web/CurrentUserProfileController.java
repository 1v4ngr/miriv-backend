package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.CurrentUserProfileResponse;
import coop.miriv.enology.identity.service.CurrentUserProfileService;
import org.springframework.web.bind.annotation.GetMapping;
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
    public CurrentUserProfileResponse currentProfile() {
        return service.getCurrentProfile();
    }
}
