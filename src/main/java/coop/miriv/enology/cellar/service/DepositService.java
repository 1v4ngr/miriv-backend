package coop.miriv.enology.cellar.service;

import coop.miriv.enology.cellar.dto.CleaningRecordResponse;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.dto.OccupationResponse;
import coop.miriv.enology.cellar.dto.UpdateDepositRequest;
import coop.miriv.enology.cellar.entity.Deposit;
import coop.miriv.enology.cellar.entity.DepositStatus;
import coop.miriv.enology.cellar.repository.DepositReadRepository;
import coop.miriv.enology.cellar.repository.DepositRepository;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.entity.Center;
import coop.miriv.enology.identity.entity.RoleCode;
import coop.miriv.enology.identity.entity.Zone;
import coop.miriv.enology.identity.repository.AppUserRepository;
import coop.miriv.enology.identity.repository.ZoneRepository;
import coop.miriv.enology.identity.service.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class DepositService {

    private static final Set<RoleCode> MANAGEMENT_ROLES = Set.of(
        RoleCode.ENOLOGIST, RoleCode.PRODUCTION_MANAGER, RoleCode.ADMIN);

    private final DepositRepository deposits;
    private final DepositReadRepository readRepository;
    private final ZoneRepository zones;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final JdbcTemplate jdbc;

    public DepositService(DepositRepository deposits, DepositReadRepository readRepository,
                          ZoneRepository zones, AppUserRepository users, CurrentUserProvider currentUser,
                          JdbcTemplate jdbc) {
        this.deposits = deposits;
        this.readRepository = readRepository;
        this.zones = zones;
        this.users = users;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DepositResponse> list() {
        AppUser user = user();
        UUID centerId = center(user).getId();
        Map<UUID, List<OccupationResponse>> occupations = readRepository.occupationsByCenter(centerId);
        Map<UUID, List<CleaningRecordResponse>> cleaning = readRepository.cleaningByCenter(centerId);
        return deposits.findAllByCenter_IdOrderByCodeAsc(centerId).stream()
            .filter(Deposit::isActive)
            .filter(deposit -> canReadZone(user, deposit.getZone()))
            .map(deposit -> response(deposit, occupations, cleaning))
            .toList();
    }

    @Transactional(readOnly = true)
    public DepositResponse get(String code) {
        AppUser user = user();
        UUID centerId = center(user).getId();
        Deposit deposit = deposits.findByCenter_IdAndCodeIgnoreCase(centerId, normalize(code))
            .filter(Deposit::isActive)
            .filter(item -> canReadZone(user, item.getZone()))
            .orElseThrow(() -> new NotFoundException("Deposit not found."));
        return response(deposit, readRepository.occupationsByCenter(centerId),
            readRepository.cleaningByCenter(centerId));
    }

    @Transactional
    public DepositResponse create(DepositRequest request) {
        AppUser user = user();
        Center center = center(user);
        if (!request.center().equalsIgnoreCase(center.getCode())
            && !request.center().equalsIgnoreCase(center.getName())) {
            throw new AccessDeniedException("The requested center is outside your scope.");
        }
        Zone zone = resolveZone(center.getId(), request.zone());
        requireManagementZone(user, zone);
        String code = normalize(request.code());
        if (deposits.existsByCenter_IdAndCodeIgnoreCase(center.getId(), code)) {
            throw new ConflictException("A deposit with this code already exists in the center.");
        }
        Deposit deposit = new Deposit();
        deposit.setCode(code);
        deposit.setCenter(center);
        deposit.setZone(zone);
        deposit.setPosition(request.position());
        deposit.setUsefulCapacityLiters(request.capacityLiters());
        deposit.setMaterial(request.material());
        deposit.setRefrigerated(request.refrigerated());
        deposit.setStatus(DepositStatus.AVAILABLE);
        deposits.saveAndFlush(deposit);
        return response(deposit, Map.of(), Map.of());
    }

    @Transactional
    public DepositResponse update(String code, UpdateDepositRequest request) {
        AppUser user = user();
        Center center = center(user);
        Deposit deposit = deposits.findForUpdate(center.getId(), normalize(code))
            .filter(Deposit::isActive)
            .orElseThrow(() -> new NotFoundException("Deposit not found."));
        requireManagementZone(user, deposit.getZone());
        Zone zone = resolveZone(center.getId(), request.zone());
        requireManagementZone(user, zone);
        BigDecimal occupied = readRepository.activeVolume(deposit.getId());
        if (request.capacityLiters().compareTo(occupied) < 0) {
            throw new BusinessRuleException("Useful capacity cannot be lower than occupied volume.");
        }
        deposit.setZone(zone);
        deposit.setPosition(request.position());
        deposit.setUsefulCapacityLiters(request.capacityLiters());
        deposit.setMaterial(request.material());
        deposit.setRefrigerated(request.refrigerated());
        deposit.setUpdatedAt(Instant.now());
        deposits.saveAndFlush(deposit);
        return response(deposit, readRepository.occupationsByCenter(center.getId()),
            readRepository.cleaningByCenter(center.getId()));
    }

    @Transactional
    public void delete(String code) {
        AppUser user = user();
        Center center = center(user);
        Deposit deposit = deposits.findForUpdate(center.getId(), normalize(code))
            .filter(Deposit::isActive)
            .orElseThrow(() -> new NotFoundException("Deposit not found."));
        requireManagementZone(user, deposit.getZone());
        UUID depositId = deposit.getId();

        jdbc.update("update incident_evidence set result_id = null where result_id in ("
                + "select r.id from result r join analysis a on a.id = r.analysis_id join sample s on s.id = a.sample_id "
                + "where s.deposit_id_at_sampling = ?)", depositId);
        jdbc.update("update incident_evidence set sample_id = null where sample_id in "
                + "(select id from sample where deposit_id_at_sampling = ?)", depositId);
        jdbc.update("update task_execution set sample_id = null where sample_id in "
                + "(select id from sample where deposit_id_at_sampling = ?)", depositId);
        jdbc.update("delete from result where analysis_id in (select a.id from analysis a join sample s on s.id = a.sample_id "
                + "where s.deposit_id_at_sampling = ?)", depositId);
        jdbc.update("delete from analysis where sample_id in (select id from sample where deposit_id_at_sampling = ?)", depositId);
        jdbc.update("delete from sample where deposit_id_at_sampling = ?", depositId);

        jdbc.update("delete from task_execution where task_id in (select id from task where deposit_id = ?)", depositId);
        jdbc.update("delete from task where deposit_id = ?", depositId);
        jdbc.update("update task set source_incident_id = null where source_incident_id in "
                + "(select id from incident where deposit_id = ?)", depositId);
        jdbc.update("delete from incident_evidence where incident_id in (select id from incident where deposit_id = ?)", depositId);
        jdbc.update("delete from incident_event where incident_id in (select id from incident where deposit_id = ?)", depositId);
        jdbc.update("delete from incident where deposit_id = ?", depositId);

        jdbc.update("delete from operation_addition where operation_id in (select id from operation where deposit_id = ?)", depositId);
        jdbc.update("delete from operation where deposit_id = ?", depositId);
        jdbc.update("delete from content_unit_lineage where movement_id in (select distinct movement_id from movement_line "
                + "where source_deposit_id = ? or destination_deposit_id = ?)", depositId, depositId);
        jdbc.update("delete from movement_line where movement_id in (select distinct movement_id from movement_line "
                + "where source_deposit_id = ? or destination_deposit_id = ?)", depositId, depositId);

        jdbc.update("delete from deposit_capacity_adjustment where deposit_id = ?", depositId);
        jdbc.update("delete from deposit_cleaning_record where deposit_id = ?", depositId);
        jdbc.update("delete from occupation where deposit_id = ?", depositId);
        deposits.delete(deposit);
    }

    private DepositResponse response(Deposit deposit,
                                     Map<UUID, List<OccupationResponse>> occupations,
                                     Map<UUID, List<CleaningRecordResponse>> cleaning) {
        String status = switch (deposit.getStatus()) {
            case PENDING_CLEANING, CLEANING -> "cleaning";
            default -> deposit.getStatus().name().toLowerCase(Locale.ROOT);
        };
        return new DepositResponse(deposit.getId(), deposit.getCode(), deposit.getCenter().getName(),
            deposit.getZone() == null ? "Unassigned" : deposit.getZone().getName(),
            deposit.getPosition() == null ? "" : deposit.getPosition(),
            deposit.getUsefulCapacityLiters(), deposit.getNominalCapacityLiters(), deposit.getMaterial(),
            deposit.isRefrigerated(), status, "none",
            occupations.getOrDefault(deposit.getId(), List.of()),
            cleaning.getOrDefault(deposit.getId(), List.of()));
    }

    private AppUser user() {
        return users.findById(currentUser.requireCurrentUserId())
            .filter(AppUser::isActive)
            .orElseThrow(() -> new AccessDeniedException("Current user is not active."));
    }

    private Center center(AppUser user) {
        if (user.getCenter() == null) {
            throw new AccessDeniedException("Current user has no assigned center.");
        }
        return user.getCenter();
    }

    private Zone resolveZone(UUID centerId, String value) {
        return zones.findByCenter_IdAndCodeIgnoreCase(centerId, value.trim())
            .or(() -> zones.findByCenter_IdAndNameIgnoreCase(centerId, value.trim()))
            .orElseThrow(() -> new NotFoundException("Zone not found in the current center."));
    }

    private boolean canReadZone(AppUser user, Zone zone) {
        return user.getRoles().stream().anyMatch(assignment -> assignment.getZone() == null
            || zone != null && assignment.getZone().getId().equals(zone.getId()));
    }

    private void requireManagementZone(AppUser user, Zone zone) {
        boolean allowed = user.getRoles().stream().anyMatch(assignment ->
            MANAGEMENT_ROLES.contains(assignment.getRole().getCode())
                && (assignment.getZone() == null || assignment.getZone().getId().equals(zone.getId())));
        if (!allowed) {
            throw new AccessDeniedException("You cannot manage deposits in this zone.");
        }
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
