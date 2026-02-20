package org.big.bigcollector.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedProblem {

    @JsonProperty("has_problem")
    private boolean hasProblem;

    private String title;
    private String description;

    @JsonProperty("problem_type")
    private String problemType;

    private String industry;

    @JsonProperty("target_customer")
    private String targetCustomer;

    @JsonProperty("pain_intensity")
    private String painIntensity;

    @JsonProperty("monetization_potential")
    private String monetizationPotential;

    @JsonProperty("monetization_model")
    private String monetizationModel;

    @JsonProperty("willingness_to_pay_signal")
    private String willingnessToPaySignal;

    @JsonProperty("key_quotes")
    private List<String> keyQuotes;

    @JsonProperty("source_url")
    private String sourceUrl;

    public boolean isValid() {
        return hasProblem
                && title != null && title.length() >= 10 && title.length() <= 200
                && description != null && !description.isBlank()
                && problemType != null && !problemType.isBlank();
    }
}
