package coop.miriv.enology.laboratory.dto;

public record PanelParameterResponse(
    String parameter,
    String unit,
    boolean required
) {}
