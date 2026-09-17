package coop.miriv.enology.laboratory.dto;

import java.util.List;

public record SampleResultResponse(
    String parameter,
    String value,
    String unit,
    String validity,
    String qualifier,
    String limit,
    String method,
    List<ResultVersionResponse> versions
) {}
