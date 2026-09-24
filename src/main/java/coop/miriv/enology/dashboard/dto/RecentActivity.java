package coop.miriv.enology.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A recent movement. {@code time} and {@code content} are the legacy one-line form; the other fields let the
 * front write it in words ("Entrada en 205 · 12.000 L de TIN-2026-003").
 */
public record RecentActivity(String time, String content, Instant at, String type, String code,
                             String source, String destination, BigDecimal liters, String lot) {}
