package com.marketingpanel.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.Norm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public final class CampaignDtos {
    private CampaignDtos() {}

    public record Variant(@NotNull Long templateId, @Min(10) @Max(90) int splitPercent) {}

    public record AbTest(boolean enabled, @Size(max = 3) List<@Valid Variant> variants, String metric, Integer testPercent, Integer winnerAfterHours) {}

    public record CampaignRequest(
            @NotBlank(message = "{vr.required}") @Size(min = 3, max = 100, message = "{vr.size}") String name,
            @NotNull(message = "Choose an approved template.") Long templateId,
            @NotBlank(message = "Choose who should receive this.") @Pattern(regexp = "ALL_OPTED_IN|TAGS|SEGMENT", message = "Choose who should receive this.") String audienceType,
            JsonNode audience,
            JsonNode variableMapping,
            String headerMediaUrl,
            String scheduledAt,
            AbTest abTest,
            boolean trackLinks) {
        public CampaignRequest {
            name = Norm.plain(Norm.name(name));
        }
    }

    public record TestSend(@NotBlank String phone) {}
}
