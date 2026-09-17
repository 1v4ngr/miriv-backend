package coop.miriv.enology.cellar.dto;

import java.time.Instant;

public record CleaningRecordResponse(Instant date, String action, String responsible, String result) {}
