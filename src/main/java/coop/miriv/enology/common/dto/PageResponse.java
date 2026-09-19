package coop.miriv.enology.common.dto;

import java.util.List;

/** F4-01: standard paginated response. {@code page} is zero-based. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}