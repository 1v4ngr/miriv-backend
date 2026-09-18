package coop.miriv.enology.catalog.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.catalog.dto.CatalogItemRequest;
import coop.miriv.enology.catalog.dto.CatalogItemResponse;
import coop.miriv.enology.catalog.entity.CatalogEntry;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * RF-CAT-01/03: every catalog shares create, list, deactivate and description handling.
 * Physical deletion is intentionally not exposed here — history must remain reconstructable,
 * so entries are deactivated instead of removed once they might have been referenced.
 */
public class CatalogCrudService<T extends CatalogEntry> {

    private final JpaRepository<T, UUID> repository;
    private final Supplier<T> factory;
    private final Function<T, String> descriptionGetter;
    private final BiConsumer<T, String> descriptionSetter;
    private final AuditService audit;
    private final String entityName;

    public CatalogCrudService(JpaRepository<T, UUID> repository, Supplier<T> factory,
                               Function<T, String> descriptionGetter, BiConsumer<T, String> descriptionSetter,
                               AuditService audit, String entityName) {
        this.repository = repository;
        this.factory = factory;
        this.descriptionGetter = descriptionGetter;
        this.descriptionSetter = descriptionSetter;
        this.audit = audit;
        this.entityName = entityName;
    }

    public static <T extends CatalogEntry> CatalogCrudService<T> withoutDescription(
        JpaRepository<T, UUID> repository, Supplier<T> factory, AuditService audit, String entityName) {
        return new CatalogCrudService<>(repository, factory, entry -> null, (entry, value) -> { }, audit, entityName);
    }

    public List<CatalogItemResponse> listActive() {
        return repository.findAll().stream()
            .filter(CatalogEntry::isActive)
            .map(this::toResponse)
            .toList();
    }

    public List<CatalogItemResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CatalogItemResponse create(CatalogItemRequest request) {
        T entry = factory.get();
        entry.setCode(request.code().trim().toUpperCase());
        entry.setName(request.name().trim());
        descriptionSetter.accept(entry, request.description());
        T saved = repository.save(entry);
        audit.record(entityName, saved.getId(), "CATALOG_CREATED", saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public CatalogItemResponse setActive(UUID id, boolean active) {
        T entry = repository.findById(id).orElseThrow(() -> NotFoundException.of("Entrada de catálogo", id));
        entry.setActive(active);
        T saved = repository.save(entry);
        audit.record(entityName, saved.getId(), active ? "CATALOG_REACTIVATED" : "CATALOG_DEACTIVATED",
            saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public void deletePhysically(UUID id) {
        T entry = repository.findById(id).orElseThrow(() -> NotFoundException.of("Entrada de catálogo", id));
        try {
            repository.delete(entry);
            repository.flush();
            audit.record(entityName, id, "CATALOG_DELETED", entry.getName());
        } catch (org.springframework.dao.DataIntegrityViolationException cause) {
            throw new BusinessRuleException("Esta entrada ya está referenciada y solo se puede desactivar (RF-CAT-03).");
        }
    }

    private CatalogItemResponse toResponse(T entry) {
        return new CatalogItemResponse(entry.getId(), entry.getCode(), entry.getName(),
            descriptionGetter.apply(entry), entry.isActive());
    }
}
