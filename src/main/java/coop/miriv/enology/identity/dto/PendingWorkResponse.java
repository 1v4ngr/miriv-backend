package coop.miriv.enology.identity.dto;

import java.util.List;

/** Open tasks/incidents assigned to a user, shown before deactivating them so they get reassigned. */
public record PendingWorkResponse(List<String> openTaskCodes, List<String> openIncidentCodes) {}
