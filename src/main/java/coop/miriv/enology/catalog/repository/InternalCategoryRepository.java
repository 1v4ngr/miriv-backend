package coop.miriv.enology.catalog.repository;

import coop.miriv.enology.catalog.entity.InternalCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InternalCategoryRepository extends JpaRepository<InternalCategory, UUID> {
    List<InternalCategory> findByActiveTrue();
}
