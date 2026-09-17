package coop.miriv.enology.common.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entityName, Object id) {
        return new NotFoundException("%s not found: %s".formatted(entityName, id));
    }
}
