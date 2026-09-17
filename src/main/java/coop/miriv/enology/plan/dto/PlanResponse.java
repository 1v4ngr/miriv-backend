package coop.miriv.enology.plan.dto;

import java.util.List;

public record PlanResponse(String contentCode, String name, String destination,
                           int currentVersion, List<PlanVersionResponse> versions) {}
