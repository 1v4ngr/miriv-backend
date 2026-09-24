package coop.miriv.enology.dashboard.dto;

/** How many of something happened on one day ({@code date} as yyyy-MM-dd in the center's timezone). */
public record DayCount(String date, int count) {}
