package coop.miriv.enology.identity.repository;

import coop.miriv.enology.identity.entity.Role;
import coop.miriv.enology.identity.entity.RoleCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(RoleCode code);
}
