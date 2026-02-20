package org.big.bigcollector.dto.hn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HNSearchResponse {

    private List<HNHit> hits;
    private int page;
    private int nbPages;
    private int nbHits;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HNHit {

        @JsonProperty("objectID")
        private String objectId;

        private String title;

        @JsonProperty("comment_text")
        private String commentText;

        @JsonProperty("story_title")
        private String storyTitle;

        @JsonProperty("story_url")
        private String storyUrl;

        private String url;
        private String author;
        private int points;

        @JsonProperty("num_comments")
        private Integer numComments;

        @JsonProperty("created_at_i")
        private long createdAtI;
    }
}
