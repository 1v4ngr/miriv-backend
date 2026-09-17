package coop.miriv.enology.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * RF-CAT-01: every catalog shares the same shape — a stable code, a display name and an
 * active flag. Deactivating a referenced entry is allowed; physical deletion is not
 * (see {@code active} handling in the service layer) unless it was never referenced.
 */
@MappedSuperclass
public abstract class CatalogEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
