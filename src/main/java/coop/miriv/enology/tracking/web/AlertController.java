package coop.miriv.enology.tracking.web;

import coop.miriv.enology.tracking.dto.TrackingDto.AlertRuleRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertRuleView;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertView;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetView;
import coop.miriv.enology.tracking.service.AlertService;
import coop.miriv.enology.tracking.service.ParameterTargetService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts, alert rules and the values fixed for one content. Reads: any authenticated user; acknowledging needs
 * INCIDENT_ACKNOWLEDGE and editing rules or fixed values needs RULE_EDIT (see PermissionRules).
 */
@RestController
public class AlertController {

    private final AlertService alerts;
    private final ParameterTargetService targets;

    public AlertController(AlertService alerts, ParameterTargetService targets) {
        this.alerts = alerts;
        this.targets = targets;
    }

    @GetMapping("/api/tracking/alerts")
    public List<AlertView> alerts() { return alerts.alerts(); }

    @PostMapping("/api/tracking/alerts/{ruleId}/contents/{content}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@PathVariable UUID ruleId, @PathVariable String content) { alerts.acknowledge(ruleId, content); }

    @GetMapping("/api/admin/alert-rules")
    public List<AlertRuleView> rules() { return alerts.rules(); }

    @PostMapping("/api/admin/alert-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleView create(@RequestBody AlertRuleRequest request) { return alerts.create(request); }

    @PutMapping("/api/admin/alert-rules/{id}")
    public AlertRuleView update(@PathVariable UUID id, @RequestBody AlertRuleRequest request) { return alerts.update(id, request); }

    @DeleteMapping("/api/admin/alert-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { alerts.delete(id); }

    @GetMapping("/api/tracking/contents/{content}/alert-rules")
    public List<AlertRuleView> contentRules(@PathVariable String content) { return alerts.rulesForContent(content); }

    @GetMapping("/api/tracking/contents/{content}/targets")
    public List<TargetView> contentTargets(@PathVariable String content) { return targets.listForContent(content); }

    @PutMapping("/api/tracking/contents/{content}/targets")
    public TargetView saveContentTarget(@PathVariable String content, @RequestBody TargetRequest request) { return targets.saveForContent(content, request); }

    @DeleteMapping("/api/tracking/contents/{content}/targets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContentTarget(@PathVariable String content, @PathVariable UUID id) { targets.deleteForContent(content, id); }
}
