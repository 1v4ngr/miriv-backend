package coop.miriv.enology.identity.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record UpdateUserCentersRequest(@NotEmpty List<String> centerCodes) {}
