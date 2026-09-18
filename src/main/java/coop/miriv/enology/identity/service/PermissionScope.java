package coop.miriv.enology.identity.service;

import java.util.Set;
import java.util.UUID;

/** Where a permission applies: every zone of the center, or only the listed zones. */
public record PermissionScope(boolean allZones, Set<UUID> zoneIds) {

    public boolean covers(UUID zoneId) {
        return allZones || (zoneId != null && zoneIds.contains(zoneId));
    }
}