package org.big.bigcollector.dto.appstore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppStoreRssResponse {

    private AppStoreFeed feed;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreFeed {
        private List<AppStoreEntry> entry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreEntry {
        private AppStoreAuthor author;
        @JsonProperty("im:rating")
        private AppStoreLabel rating;
        @JsonProperty("im:version")
        private AppStoreLabel version;
        private AppStoreId id;
        private AppStoreLabel title;
        private AppStoreLabel content;
        private AppStoreLabel updated;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreAuthor {
        private AppStoreLabel name;
        private AppStoreLabel uri;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreId {
        private String label;
        private AppStoreIdAttributes attributes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreIdAttributes {
        @JsonProperty("im:id")
        private String imId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppStoreLabel {
        private String label;
    }
}
