package coop.miriv.enology.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.identity.service.PermissionScope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneScopeTest {

    private static final UUID ZONE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ZONE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void coversEverythingWhenAllZonesIsTrue() {
        PermissionScope scope = new PermissionScope(true, Set.of());
        assertTrue(scope.covers(ZONE_A));
        assertTrue(scope.covers(ZONE_B));
        assertTrue(scope.covers(null));
    }

    @Test
    void onlyListedZonesWhenScoped() {
        PermissionScope scope = new PermissionScope(false, Set.of(ZONE_A));
        assertTrue(scope.covers(ZONE_A));
        assertFalse(scope.covers(ZONE_B));
    }

    @Test
    void emptyListWithRestrictedVisibilityBlocksAll() {
        PermissionScope scope = new PermissionScope(false, Set.of());
        assertFalse(scope.covers(ZONE_A));
        assertFalse(scope.covers(null));
    }
}