package coop.miriv.enology.cellar.service;

import coop.miriv.enology.cellar.dto.CompleteCleaningRequest;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositCleaningService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final DepositService deposits;

    public DepositCleaningService(JdbcTemplate jdbc, CurrentUserContext context, DepositService deposits) {
        this.jdbc = jdbc;
        this.context = context;
        this.deposits = deposits;
    }

    @Transactional
    public DepositResponse start(String code) {
        DepositLock deposit = lock(code);
        if (!deposit.status().equals("PENDING_CLEANING")) {
            throw new BusinessRuleException("Only deposits pending cleaning can start cleaning.");
        }
        jdbc.update("update deposit set status = 'CLEANING'::deposit_status, updated_at = now() where id = ?",
            deposit.id());
        return deposits.get(code);
    }

    @Transactional
    public DepositResponse complete(String code, CompleteCleaningRequest request) {
        DepositLock deposit = lock(code);
        if (!deposit.status().equals("PENDING_CLEANING") && !deposit.status().equals("CLEANING")) {
            throw new BusinessRuleException("Deposit is not in a cleaning workflow.");
        }
        boolean occupied = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from occupation "
            + "where deposit_id = ? and end_at is null)", Boolean.class, deposit.id()));
        if (occupied) throw new BusinessRuleException("Occupied deposits cannot be released from cleaning.");
        jdbc.update("insert into deposit_cleaning_record(id, deposit_id, action, responsible_id, result, notes) "
                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), deposit.id(), request.action().trim(),
            context.userId(), request.result().trim(), request.notes());
        String nextStatus = request.approved() ? "AVAILABLE" : "PENDING_CLEANING";
        jdbc.update("update deposit set status = cast(? as deposit_status), updated_at = now() where id = ?",
            nextStatus, deposit.id());
        return deposits.get(code);
    }

    private DepositLock lock(String code) {
        UUID centerId = context.centerId();
        List<DepositLock> rows = jdbc.query("select id, zone_id, status::text from deposit where center_id = ? "
                + "and code = ? and active = true for update",
            (rs, index) -> new DepositLock(rs.getObject("id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("status")),
            centerId, code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""));
        if (rows.isEmpty()) throw new NotFoundException("Deposit not found.");
        context.requireInZone("DEPOSIT_CLEANING", rows.getFirst().zoneId());
        return rows.getFirst();
    }

    private record DepositLock(UUID id, UUID zoneId, String status) {}
}
