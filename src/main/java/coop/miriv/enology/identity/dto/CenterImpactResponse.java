package coop.miriv.enology.identity.dto;

import java.util.List;
import java.util.Map;

/** What deleting a center would remove: counts per kind of data and the accounts that go with it. */
public record CenterImpactResponse(String code, Map<String, Integer> counts, List<String> usersDeleted, boolean canPurge) {}
