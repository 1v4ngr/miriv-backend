package coop.miriv.enology.catalog.repository;

import coop.miriv.enology.catalog.entity.ProductType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTypeRepository extends JpaRepository<ProductType, UUID> {
    List<ProductType> findByActiveTrue();
}
