package coop.miriv.enology.catalog.service;

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

    public CatalogCrudService(JpaRepository<T, UUID> repository, Supplier<T> factory,
                               Function<T, String> descriptionGetter, BiConsumer<T, String> descriptionSetter) {
        this.repository = repository;
        this.factory = factory;
        this.descriptionGetter = descriptionGetter;
        this.descriptionSetter = descriptionSetter;
    }

    public static <T extends CatalogEntry> CatalogCrudService<T> withoutDescription(
        JpaRepository<T, UUID> repository, Supplier<T> factory) {
        return new CatalogCrudService<>(repository, factory, entry -> null, (entry, value) -> { });
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
        return toResponse(repository.save(entry));
    }

    @Transactional
    public CatalogItemResponse setActive(UUID id, boolean active) {
        T entry = repository.findById(id).orElseThrow(() -> NotFoundException.of("Catalog entry", id));
        entry.setActive(active);
        return toResponse(repository.save(entry));
    }

    @Transactional
    public void deletePhysically(UUID id) {
        T entry = repository.findById(id).orElseThrow(() -> NotFoundException.of("Catalog entry", id));
        try {
            repository.delete(entry);
            repository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException cause) {
            throw new BusinessRuleException(
                "This entry has already been referenced and can only be deactivated (RF-CAT-03).");
        }
    }

    private CatalogItemResponse toResponse(T entry) {
        return new CatalogItemResponse(entry.getId(), entry.getCode(), entry.getName(),
            descriptionGetter.apply(entry), entry.isActive());
    }
}
