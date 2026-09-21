package coop.miriv.enology.catalog.web;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.catalog.dto.CatalogItemRequest;
import coop.miriv.enology.catalog.dto.CatalogItemResponse;
import coop.miriv.enology.catalog.entity.Destination;
import coop.miriv.enology.catalog.entity.InternalCategory;
import coop.miriv.enology.catalog.entity.Variety;
import coop.miriv.enology.catalog.repository.DestinationRepository;
import coop.miriv.enology.catalog.repository.InternalCategoryRepository;
import coop.miriv.enology.catalog.repository.VarietyRepository;
import coop.miriv.enology.catalog.service.CatalogCrudService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-CAT-01..03: destination, internal category and variety catalogs (the only ones with actual use). */
@RestController
@RequestMapping("/api/catalogs")
public class CatalogController {

    private final CatalogCrudService<Destination> destinations;
    private final CatalogCrudService<InternalCategory> internalCategories;
    private final CatalogCrudService<Variety> varieties;

    public CatalogController(DestinationRepository destinationRepository,
                              InternalCategoryRepository internalCategoryRepository,
                              VarietyRepository varietyRepository,
                              AuditService audit) {
        this.destinations = CatalogCrudService.withoutDescription(destinationRepository, Destination::new, audit, "destination");
        this.internalCategories = new CatalogCrudService<>(internalCategoryRepository, InternalCategory::new,
            InternalCategory::getDescription, InternalCategory::setDescription, audit, "internal_category");
        this.varieties = CatalogCrudService.withoutDescription(varietyRepository, Variety::new, audit, "variety");
    }

    @GetMapping("/destinations")
    public List<CatalogItemResponse> destinations(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return listFor(destinations, includeInactive);
    }

    @PostMapping("/destinations")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createDestination(@Valid @RequestBody CatalogItemRequest request) {
        return destinations.create(request);
    }

    @PutMapping("/destinations/{id}/active")
    public CatalogItemResponse setDestinationActive(@PathVariable UUID id, @RequestBody boolean active) {
        return destinations.setActive(id, active);
    }

    @GetMapping("/internal-categories")
    public List<CatalogItemResponse> internalCategories(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return listFor(internalCategories, includeInactive);
    }

    @PostMapping("/internal-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createInternalCategory(@Valid @RequestBody CatalogItemRequest request) {
        return internalCategories.create(request);
    }

    @PutMapping("/internal-categories/{id}/active")
    public CatalogItemResponse setInternalCategoryActive(@PathVariable UUID id, @RequestBody boolean active) {
        return internalCategories.setActive(id, active);
    }

    @GetMapping("/varieties")
    public List<CatalogItemResponse> varieties(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return listFor(varieties, includeInactive);
    }

    @PostMapping("/varieties")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createVariety(@Valid @RequestBody CatalogItemRequest request) {
        return varieties.create(request);
    }

    @PutMapping("/varieties/{id}/active")
    public CatalogItemResponse setVarietyActive(@PathVariable UUID id, @RequestBody boolean active) {
        return varieties.setActive(id, active);
    }

    private <T extends coop.miriv.enology.catalog.entity.CatalogEntry> List<CatalogItemResponse> listFor(CatalogCrudService<T> service, boolean includeInactive) {
        if (!includeInactive) return service.listActive();
        boolean catalogManager = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch((authority) -> authority.getAuthority().equals("PERM_CATALOG_MANAGE")
                || authority.getAuthority().equals("PERM_LAB_CATALOG_MANAGE"));
        if (!catalogManager) throw new AccessDeniedException("Solo los administradores pueden listar elementos inactivos del catálogo.");
        return service.listAll();
    }
}