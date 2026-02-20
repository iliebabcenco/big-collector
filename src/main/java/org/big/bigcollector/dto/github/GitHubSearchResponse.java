package org.big.bigcollector.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubSearchResponse {

    @JsonProperty("total_count")
    private int totalCount;

    @JsonProperty("incomplete_results")
    private boolean incompleteResults;

    private List<GitHubIssue> items;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubIssue {

        private long id;
        private String title;
        private String body;

        @JsonProperty("html_url")
        private String htmlUrl;

        private int comments;
        private List<GitHubLabel> labels;
        private GitHubReactions reactions;

        @JsonProperty("repository_url")
        private String repositoryUrl;

        private GitHubUser user;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubLabel {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubReactions {

        @JsonProperty("total_count")
        private int totalCount;

        @JsonProperty("+1")
        private int plusOne;

        @JsonProperty("-1")
        private int minusOne;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubUser {
        private String login;
    }
}
