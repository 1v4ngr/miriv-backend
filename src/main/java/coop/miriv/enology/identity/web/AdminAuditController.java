package coop.miriv.enology.identity.web;
import coop.miriv.enology.identity.dto.AuditEntryResponse;
import coop.miriv.enology.identity.service.AdminAuditService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/api/admin/audit") public class AdminAuditController { private final AdminAuditService service; public AdminAuditController(AdminAuditService service){this.service=service;} @GetMapping public List<AuditEntryResponse> list(){return service.list();} }
