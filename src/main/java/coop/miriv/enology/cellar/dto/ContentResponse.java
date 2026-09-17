package coop.miriv.enology.cellar.dto;

import coop.miriv.enology.laboratory.dto.SampleResponse;
import java.util.List;

public record ContentResponse(
    String code,
    LotResponse lot,
    DepositResponse deposit,
    OccupationResponse occupation,
    boolean active,
    String plan,
    FermentationStateResponse alcoholic,
    FermentationStateResponse malolactic,
    List<SampleResponse> samples
) {}
