package coop.miriv.enology.dashboard.dto;

import java.util.List;

public record WorkHomeResponse(String center, String campaign, String updatedAt, int urgentCount,
                               int attentionTotal, List<DashboardMetric> metrics,
                               List<AttentionItem> attentionItems, OwnTasks ownTasks,
                               List<RecentActivity> recentActivity) {}
