package coop.miriv.enology.cellar.service;

import coop.miriv.enology.audit.AuditService;
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
import coop.miriv.enology.identity.entity.Center;
import coop.miriv.enology.identity.entity.Zone;
import coop.miriv.enology.identity.repository.ZoneRepository;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class DepositService {

    private final DepositRepository deposits;
    private final DepositReadRepository readRepository;
    private final ZoneRepository zones;
    private final CurrentUserContext context;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DepositService(DepositRepository deposits, DepositReadRepository readRepository,
                          ZoneRepository zones, CurrentUserContext context, JdbcTemplate jdbc, AuditService audit) {
        this.deposits = deposits;
        this.readRepository = readRepository;
        this.zones = zones;
        this.context = context;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepositResponse> list() {
        UUID centerId = context.centerId();
        Map<UUID, List<OccupationResponse>> occupations = readRepository.occupationsByCenter(centerId);
        Map<UUID, List<CleaningRecordResponse>> cleaning = readRepository.cleaningByCenter(centerId);
        return deposits.findAllByCenter_IdOrderByCodeAsc(centerId).stream()
            .filter(Deposit::isActive)
            .filter(deposit -> context.canRead(deposit.getZone() == null ? null : deposit.getZone().getId()))
            .map(deposit -> response(deposit, occupations, cleaning))
            .toList();
    }

    @Transactional(readOnly = true)
    public DepositResponse get(String code) {
        UUID centerId = context.centerId();
        Deposit deposit = deposits.findByCenter_IdAndCodeIgnoreCase(centerId, normalize(code))
            .filter(Deposit::isActive)
            .filter(item -> context.canRead(item.getZone() == null ? null : item.getZone().getId()))
            .orElseThrow(() -> new NotFoundException("Depósito no encontrado."));
        // F2-07: read just this deposit's occupations and cleaning rows instead of loading
        // the whole center. The list view still uses the by-center versions because it
        // already iterates every deposit in memory anyway.
        return response(deposit,
            Map.of(deposit.getId(), readRepository.occupationsByDeposit(deposit.getId())),
            Map.of(deposit.getId(), readRepository.cleaningByDeposit(deposit.getId())));
    }

    @Transactional
    public DepositResponse create(DepositRequest request) {
        Center center = context.center();
        if (!request.center().equalsIgnoreCase(center.getCode())
            && !request.center().equalsIgnoreCase(center.getName())) {
            throw new AccessDeniedException("El centro solicitado está fuera de tu ámbito.");
        }
        Zone zone = resolveZone(center.getId(), request.zone());
        context.requireInZone("DEPOSIT_MANAGE", zone.getId());
        String code = normalize(request.code());
        if (deposits.existsByCenter_IdAndCodeIgnoreCase(center.getId(), code)) {
            throw new ConflictException("DUPLICATE_CODE", "Ya existe un depósito con ese código en el centro.");
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
        audit.record("deposit", deposit.getId(), "DEPOSIT_CREATED",
            "Depósito " + deposit.getCode() + " creado en zona " + zone.getCode());
        return response(deposit, Map.of(), Map.of());
    }

    @Transactional
    public DepositResponse update(String code, UpdateDepositRequest request) {
        Center center = context.center();
        Deposit deposit = deposits.findForUpdate(center.getId(), normalize(code))
            .filter(Deposit::isActive)
            .orElseThrow(() -> new NotFoundException("Depósito no encontrado."));
        // A deposit without a zone yet can be placed by anyone who manages the target zone.
        Zone previous = deposit.getZone();
        if (previous != null) context.requireInZone("DEPOSIT_MANAGE", previous.getId());
        Zone zone = resolveZone(center.getId(), request.zone());
        context.requireInZone("DEPOSIT_MANAGE", zone.getId());
        BigDecimal occupied = readRepository.activeVolume(deposit.getId());
        if (request.capacityLiters().compareTo(occupied) < 0) {
            throw new BusinessRuleException("La capacidad útil no puede ser inferior al volumen ocupado.");
        }
        deposit.setZone(zone);
        deposit.setPosition(request.position());
        deposit.setUsefulCapacityLiters(request.capacityLiters());
        deposit.setMaterial(request.material());
        deposit.setRefrigerated(request.refrigerated());
        deposit.setUpdatedAt(Instant.now());
        deposits.saveAndFlush(deposit);
        if (previous == null || !previous.getId().equals(zone.getId())) {
            audit.record("deposit", deposit.getId(), "DEPOSIT_MOVED", "Depósito " + deposit.getCode() + " movido de "
                + (previous == null ? "sin zona" : previous.getName()) + " a " + zone.getName());
        }
        return response(deposit, readRepository.occupationsByCenter(center.getId()),
            readRepository.cleaningByCenter(center.getId()));
    }

    @Transactional
    public DepositResponse deactivate(String code) {
        Center center = context.center();
        Deposit deposit = deposits.findForUpdate(center.getId(), normalize(code))
            .filter(Deposit::isActive)
            .orElseThrow(() -> new NotFoundException("Depósito no encontrado."));
        context.requireInZone("DEPOSIT_MANAGE", deposit.getZone().getId());
        if (readRepository.activeVolume(deposit.getId()).signum() > 0) {
            throw new BusinessRuleException("No se puede desactivar un depósito con contenido.");
        }
        deposit.setActive(false);
        deposit.setUpdatedAt(Instant.now());
        deposits.saveAndFlush(deposit);
        audit.record("deposit", deposit.getId(), "DEPOSIT_DEACTIVATED", "Depósito " + deposit.getCode() + " desactivado");
        return response(deposit, Map.of(), Map.of());
    }

    private DepositResponse response(Deposit deposit,
                                     Map<UUID, List<OccupationResponse>> occupations,
                                     Map<UUID, List<CleaningRecordResponse>> cleaning) {
        String status = deposit.getStatus().name().toLowerCase(Locale.ROOT);
        return new DepositResponse(deposit.getId(), deposit.getCode(), deposit.getCenter().getName(),
            deposit.getZone() == null ? null : deposit.getZone().getName(),
            deposit.getPosition() == null ? "" : deposit.getPosition(),
            deposit.getUsefulCapacityLiters(), deposit.getNominalCapacityLiters(), deposit.getMaterial(),
            deposit.isRefrigerated(), status, "NONE",
            occupations.getOrDefault(deposit.getId(), List.of()),
            cleaning.getOrDefault(deposit.getId(), List.of()));
    }

    private Zone resolveZone(UUID centerId, String value) {
        return zones.findByCenter_IdAndCodeIgnoreCase(centerId, value.trim())
            .or(() -> zones.findByCenter_IdAndNameIgnoreCase(centerId, value.trim()))
            .orElseThrow(() -> new NotFoundException("Zona no encontrada en el centro actual."));
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
