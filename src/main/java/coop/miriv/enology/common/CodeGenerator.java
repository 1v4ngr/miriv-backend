package coop.miriv.enology.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F2-05: generates human-readable, sequential, monotonically-increasing codes
 * such as {@code MOV-2026-00001} or {@code TSK-2026-00007}. Backed by the
 * {@code code_sequence} table introduced in V25, which is upserted atomically
 * so concurrent inserts never collide.
 */
@Component
public class CodeGenerator {

    private final JdbcTemplate jdbc;

    public CodeGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param prefix 2-10 char code family, e.g. {@code "MOV"}, {@code "TSK"}, {@code "C"}, {@code "MIX"}.
     * @param year   campaign / calendar year, e.g. {@code 2026}. Sequence restarts each year.
     * @return the next sequential code in the form {@code PREFIX-YYYY-NNNNN}.
     */
    public String next(String prefix, int year) {
        Integer value = jdbc.queryForObject("""
            insert into code_sequence(prefix, year, last_value) values (?, ?, 1)
            on conflict (prefix, year) do update set last_value = code_sequence.last_value + 1
            returning last_value
            """, Integer.class, prefix, year);
        return "%s-%d-%05d".formatted(prefix, year, value);
    }
}
