package coop.miriv.enology.identity.dto;

import java.util.List;

public record AdminUserResponse(String username, String displayName, String email,
                                String primaryCenterCode, List<CenterOption> centers) {}
