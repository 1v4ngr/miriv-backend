package coop.miriv.enology.catalog.web;

import coop.miriv.enology.catalog.dto.CatalogItemRequest;
import coop.miriv.enology.catalog.dto.CatalogItemResponse;
import coop.miriv.enology.catalog.entity.Color;
import coop.miriv.enology.catalog.entity.Destination;
import coop.miriv.enology.catalog.entity.InternalCategory;
import coop.miriv.enology.catalog.entity.ProductType;
import coop.miriv.enology.catalog.entity.Variety;
import coop.miriv.enology.catalog.repository.ColorRepository;
import coop.miriv.enology.catalog.repository.DestinationRepository;
import coop.miriv.enology.catalog.repository.InternalCategoryRepository;
import coop.miriv.enology.catalog.repository.ProductTypeRepository;
import coop.miriv.enology.catalog.repository.VarietyRepository;
import coop.miriv.enology.catalog.service.CatalogCrudService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-CAT-01..03: product type, color, destination, internal category and variety catalogs. */
@RestController
@RequestMapping("/api/catalogs")
public class CatalogController {

    private final CatalogCrudService<ProductType> productTypes;
    private final CatalogCrudService<Color> colors;
    private final CatalogCrudService<Destination> destinations;
    private final CatalogCrudService<InternalCategory> internalCategories;
    private final CatalogCrudService<Variety> varieties;

    public CatalogController(ProductTypeRepository productTypeRepository, ColorRepository colorRepository,
                              DestinationRepository destinationRepository,
                              InternalCategoryRepository internalCategoryRepository,
                              VarietyRepository varietyRepository) {
        this.productTypes = new CatalogCrudService<>(productTypeRepository, ProductType::new,
            ProductType::getDescription, ProductType::setDescription);
        this.colors = CatalogCrudService.withoutDescription(colorRepository, Color::new);
        this.destinations = CatalogCrudService.withoutDescription(destinationRepository, Destination::new);
        this.internalCategories = new CatalogCrudService<>(internalCategoryRepository, InternalCategory::new,
            InternalCategory::getDescription, InternalCategory::setDescription);
        this.varieties = CatalogCrudService.withoutDescription(varietyRepository, Variety::new);
    }

    @GetMapping("/product-types")
    public List<CatalogItemResponse> productTypes() {
        return productTypes.listActive();
    }

    @PostMapping("/product-types")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createProductType(@Valid @RequestBody CatalogItemRequest request) {
        return productTypes.create(request);
    }

    @PutMapping("/product-types/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogItemResponse setProductTypeActive(@PathVariable UUID id, @RequestBody boolean active) {
        return productTypes.setActive(id, active);
    }

    @DeleteMapping("/product-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProductType(@PathVariable UUID id) {
        productTypes.deletePhysically(id);
    }

    @GetMapping("/colors")
    public List<CatalogItemResponse> colors() {
        return colors.listActive();
    }

    @PostMapping("/colors")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createColor(@Valid @RequestBody CatalogItemRequest request) {
        return colors.create(request);
    }

    @PutMapping("/colors/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogItemResponse setColorActive(@PathVariable UUID id, @RequestBody boolean active) {
        return colors.setActive(id, active);
    }

    @GetMapping("/destinations")
    public List<CatalogItemResponse> destinations() {
        return destinations.listActive();
    }

    @PostMapping("/destinations")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createDestination(@Valid @RequestBody CatalogItemRequest request) {
        return destinations.create(request);
    }

    @PutMapping("/destinations/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogItemResponse setDestinationActive(@PathVariable UUID id, @RequestBody boolean active) {
        return destinations.setActive(id, active);
    }

    @GetMapping("/internal-categories")
    public List<CatalogItemResponse> internalCategories() {
        return internalCategories.listActive();
    }

    @PostMapping("/internal-categories")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createInternalCategory(@Valid @RequestBody CatalogItemRequest request) {
        return internalCategories.create(request);
    }

    @PutMapping("/internal-categories/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogItemResponse setInternalCategoryActive(@PathVariable UUID id, @RequestBody boolean active) {
        return internalCategories.setActive(id, active);
    }

    @GetMapping("/varieties")
    public List<CatalogItemResponse> varieties() {
        return varieties.listActive();
    }

    @PostMapping("/varieties")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createVariety(@Valid @RequestBody CatalogItemRequest request) {
        return varieties.create(request);
    }

    @PutMapping("/varieties/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogItemResponse setVarietyActive(@PathVariable UUID id, @RequestBody boolean active) {
        return varieties.setActive(id, active);
    }
}
