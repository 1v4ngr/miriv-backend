package coop.miriv.enology.identity.repository;

import coop.miriv.enology.identity.entity.Zone;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<Zone, UUID> {
    Optional<Zone> findByCenter_IdAndNameIgnoreCase(UUID centerId, String name);

    Optional<Zone> findByCenter_IdAndCodeIgnoreCase(UUID centerId, String code);
}
