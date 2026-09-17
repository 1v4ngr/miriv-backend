package coop.miriv.enology.catalog.repository;

import coop.miriv.enology.catalog.entity.Color;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ColorRepository extends JpaRepository<Color, UUID> {
    List<Color> findByActiveTrue();
}
