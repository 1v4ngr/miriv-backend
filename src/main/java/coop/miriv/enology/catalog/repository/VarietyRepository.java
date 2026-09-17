package coop.miriv.enology.catalog.repository;

import coop.miriv.enology.catalog.entity.Variety;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VarietyRepository extends JpaRepository<Variety, UUID> {
    List<Variety> findByActiveTrue();
}
