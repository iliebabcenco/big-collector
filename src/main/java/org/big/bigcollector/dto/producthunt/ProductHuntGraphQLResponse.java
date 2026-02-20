package org.big.bigcollector.dto.producthunt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductHuntGraphQLResponse {

    private ProductHuntData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductHuntData {
        private PostsConnection posts;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PostsConnection {
        private List<PostEdge> edges;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PostEdge {
        private Post node;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Post {
        private String id;
        private String name;
        private String tagline;
        private String description;
        private String url;
        private int votesCount;
        private CommentsConnection comments;
        private List<Topic> topics;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommentsConnection {
        private List<CommentEdge> edges;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommentEdge {
        private Comment node;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Comment {
        private String id;
        private String body;
        private User user;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        private String name;
        private String username;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Topic {
        private String name;
    }
}
