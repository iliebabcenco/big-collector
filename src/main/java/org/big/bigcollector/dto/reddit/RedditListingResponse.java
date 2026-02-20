package org.big.bigcollector.dto.reddit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedditListingResponse {

    private RedditData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RedditData {
        private String after;
        private List<RedditChild> children;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RedditChild {
        private String kind;
        private RedditPost data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RedditPost {
        private String id;
        private String title;
        private String selftext;
        private int score;
        private String subreddit;
        private String permalink;
        private String author;

        @JsonProperty("num_comments")
        private int numComments;

        @JsonProperty("created_utc")
        private double createdUtc;
    }
}
