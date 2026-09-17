package coop.miriv.enology.cellar.repository;

import coop.miriv.enology.cellar.entity.Deposit;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositRepository extends JpaRepository<Deposit, UUID> {

    @EntityGraph(attributePaths = {"center", "zone"})
    List<Deposit> findAllByCenter_IdOrderByCodeAsc(UUID centerId);

    @EntityGraph(attributePaths = {"center", "zone"})
    Optional<Deposit> findByCenter_IdAndCodeIgnoreCase(UUID centerId, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"center", "zone"})
    @Query("select d from Deposit d where d.center.id = :centerId and upper(d.code) = upper(:code)")
    Optional<Deposit> findForUpdate(@Param("centerId") UUID centerId, @Param("code") String code);

    boolean existsByCenter_IdAndCodeIgnoreCase(UUID centerId, String code);
}
