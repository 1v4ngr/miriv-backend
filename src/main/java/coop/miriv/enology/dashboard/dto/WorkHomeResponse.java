package coop.miriv.enology.dashboard.dto;

import java.util.List;

public record WorkHomeResponse(String center, String campaign, String updatedAt,
                               List<DashboardMetric> metrics,
                               List<RecentActivity> recentActivity) {}