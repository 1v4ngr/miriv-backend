package coop.miriv.enology.identity.repository;

import coop.miriv.enology.identity.entity.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsernameAndActiveTrue(String username);

    Optional<AppUser> findByEmailIgnoreCaseAndActiveTrue(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
