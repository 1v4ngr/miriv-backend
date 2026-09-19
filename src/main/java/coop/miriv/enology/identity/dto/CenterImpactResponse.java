package coop.miriv.enology.identity.dto;

import java.util.List;
import java.util.Map;

/**
 * What deleting a center would remove: counts per kind of data and the accounts that go with it.
 * Super administrators are never removed; they move to {@code fallbackCenter} (null = last center).
 */
public record CenterImpactResponse(String code, Map<String, Integer> counts, List<String> usersDeleted,
                                   List<String> superAdminsMoved, String fallbackCenter, boolean canPurge) {}
