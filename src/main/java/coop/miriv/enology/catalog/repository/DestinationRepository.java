package coop.miriv.enology.catalog.repository;

import coop.miriv.enology.catalog.entity.Destination;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DestinationRepository extends JpaRepository<Destination, UUID> {
    List<Destination> findByActiveTrue();
}
