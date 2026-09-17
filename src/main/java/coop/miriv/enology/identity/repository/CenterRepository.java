package coop.miriv.enology.identity.repository;

import coop.miriv.enology.identity.entity.Center;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CenterRepository extends JpaRepository<Center, UUID> {
}
