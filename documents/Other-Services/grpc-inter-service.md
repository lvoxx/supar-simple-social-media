# gRPC — Inter-Service Communication (additions for new services)

> Tài liệu này bổ sung và cập nhật proto definitions cho tất cả services mới.  
> Xem `grpc-inter-service.md` gốc để biết tool stack, module layout, server/client patterns.

---

## Updated proto module layout

```
spring-services/
└── proto/
    └── src/main/proto/
        ├── common/
        │   └── common.proto
        ├── user/
        │   └── user_service.proto
        ├── post/
        │   └── post_service.proto              ← thêm FindPostsByIds (batch cho feed)
        ├── group/
        │   └── group_service.proto
        ├── media/
        │   └── media_service.proto
        ├── message/
        │   └── conversation_service.proto
        ├── interaction/
        │   └── post_interaction_service.proto   ← NEW
        ├── bookmark/
        │   └── bookmark_service.proto           ← NEW
        └── recommendation/
            ├── post_recommendation_service.proto    ← NEW
            └── comment_recommendation_service.proto ← NEW
```

---

## Updated: post/post_service.proto

```protobuf
syntax = "proto3";
package sssm.post;
option java_package         = "io.github.lvoxx.proto.post";
option java_multiple_files  = true;

service PostService {
  rpc FindPostById     (FindPostByIdRequest)    returns (PostResponse);
  rpc CheckPostExists  (CheckPostExistsRequest) returns (CheckPostExistsResponse);
  rpc FindPostsByIds   (FindPostsByIdsRequest)  returns (PostListResponse);   // NEW — batch fetch cho feed/recommendation
}

message FindPostByIdRequest    { string post_id = 1; }
message CheckPostExistsRequest { string post_id = 1; }
message FindPostsByIdsRequest  { repeated string post_ids = 1; }

message PostResponse {
  string post_id        = 1;
  string author_id      = 2;
  string content        = 3;
  string status         = 4;
  string post_type      = 5;
  string repost_of_id   = 6;
  string quoted_post_id = 7;
  int64  created_at_ms  = 8;
}
message PostListResponse       { repeated PostResponse posts = 1; }
message CheckPostExistsResponse { bool exists = 1; }
```

---

## New: interaction/post_interaction_service.proto

```protobuf
syntax = "proto3";
package sssm.interaction;
option java_package        = "io.github.lvoxx.proto.interaction";
option java_multiple_files = true;

service PostInteractionService {

  // Đọc display counts (Redis-first) cho 1 post
  rpc GetPostCounts      (GetPostCountsRequest)      returns (PostCountsResponse);

  // Batch đọc display counts cho nhiều post (phục vụ feed + recommendation)
  rpc BatchGetPostCounts (BatchGetPostCountsRequest)  returns (BatchPostCountsResponse);

  // Kiểm tra user đã like/share/bookmark post chưa
  rpc GetUserInteraction (GetUserInteractionRequest)  returns (UserInteractionResponse);

  // Ghi nhận share từ post-service khi tạo REPOST/QUOTE
  rpc RegisterShare      (RegisterShareRequest)       returns (RegisterShareResponse);

  // Ghi nhận bookmark signal từ bookmark-service
  rpc RegisterBookmarkSignal (RegisterBookmarkSignalRequest) returns (RegisterBookmarkSignalResponse);
}

message GetPostCountsRequest         { string post_id = 1; }
message BatchGetPostCountsRequest    { repeated string post_ids = 1; }   // max 100
message GetUserInteractionRequest    { string post_id = 1; string user_id = 2; }
message RegisterShareRequest         {
  string post_id        = 1;
  string actor_id       = 2;
  string shared_post_id = 3;
}
message RegisterBookmarkSignalRequest {
  string post_id    = 1;
  string user_id    = 2;
  bool   bookmarked = 3;
}

message PostCountsResponse {
  string like_display     = 1;   // "1.2K"
  string share_display    = 2;
  string bookmark_display = 3;
  string view_display     = 4;
  int64  like_raw         = 5;   // raw — dùng cho recommendation scoring
  int64  share_raw        = 6;
  int64  view_raw         = 7;
  int64  bookmark_raw     = 8;
}
message BatchPostCountsResponse {
  map<string, PostCountsResponse> counts = 1;   // postId → counts
}
message UserInteractionResponse {
  bool liked      = 1;
  bool shared     = 2;
  bool bookmarked = 3;
}
message RegisterShareResponse          { bool success = 1; }
message RegisterBookmarkSignalResponse { bool success = 1; }
```

---

## New: bookmark/bookmark_service.proto

```protobuf
syntax = "proto3";
package sssm.bookmark;
option java_package        = "io.github.lvoxx.proto.bookmark";
option java_multiple_files = true;

service BookmarkService {

  // Kiểm tra user đã bookmark 1 post chưa
  rpc CheckBookmarked      (CheckBookmarkedRequest)      returns (CheckBookmarkedResponse);

  // Batch check cho feed rendering
  rpc BatchCheckBookmarked (BatchCheckBookmarkedRequest) returns (BatchCheckBookmarkedResponse);
}

message CheckBookmarkedRequest {
  string post_id = 1;
  string user_id = 2;
}
message CheckBookmarkedResponse {
  bool   bookmarked     = 1;
  string collection_id  = 2;
}
message BatchCheckBookmarkedRequest {
  repeated string post_ids = 1;
  string          user_id  = 2;
}
message BatchCheckBookmarkedResponse {
  map<string, bool> bookmarked_map = 1;
}
```

---

## New: recommendation/post_recommendation_service.proto

```protobuf
syntax = "proto3";
package sssm.recommendation;
option java_package        = "io.github.lvoxx.proto.recommendation";
option java_multiple_files = true;

service PostRecommendationService {

  // Rerank candidate posts cho 1 user — called by post-service trước khi return feed
  rpc RankPosts  (RankPostsRequest)  returns (RankPostsResponse);

  // Batch score không rerank — cho admin/analytics
  rpc ScorePosts (ScorePostsRequest) returns (ScorePostsResponse);
}

message CandidatePost {
  string post_id       = 1;
  string author_id     = 2;
  int64  created_at_ms = 3;
  int64  like_raw      = 4;
  int64  share_raw     = 5;
  int32  comment_count = 6;
  int64  view_raw      = 7;
  int32  bookmark_raw  = 8;
}

message RankPostsRequest {
  string                 user_id     = 1;
  repeated CandidatePost candidates  = 2;
  int32                  return_size = 3;   // default = 20
  string                 feed_type   = 4;   // HOME | EXPLORE | PROFILE
}

message RankPostsResponse {
  repeated string ranked_post_ids = 1;
  string          model_version   = 2;
  bool            is_fallback     = 3;
}

message ScorePostsRequest  { repeated CandidatePost posts = 1; string user_id = 2; }
message ScorePostsResponse { map<string, float> scores = 1; }
```

---

## New: recommendation/comment_recommendation_service.proto

```protobuf
syntax = "proto3";
package sssm.recommendation;

service CommentRecommendationService {

  // Rerank candidate comments — called by comment-service trước khi return sort=BEST
  rpc RankComments (RankCommentsRequest) returns (RankCommentsResponse);
}

message CandidateComment {
  string comment_id     = 1;
  string author_id      = 2;
  int64  created_at_ms  = 3;
  int32  reaction_count = 4;
  int32  reply_count    = 5;
  int64  view_count     = 6;
  int32  depth          = 7;
  int32  content_length = 8;
}

message RankCommentsRequest {
  string                   post_id     = 1;
  string                   user_id     = 2;
  repeated CandidateComment candidates = 3;
  int32                    return_size = 4;
}

message RankCommentsResponse {
  repeated string ranked_comment_ids = 1;
  string          model_version      = 2;
  bool            is_fallback        = 3;
}
```

---

## gRPC Service Registry (full, updated)

| Service                        | Implements (server)            | Calls (client)                                                                                                                            |
| ------------------------------ | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| user-service                   | `UserService`                  | —                                                                                                                                         |
| post-service                   | `PostService`                  | `UserService`, `PostInteractionService.BatchGetPostCounts`, `PostRecommendationService.RankPosts`, `PostInteractionService.RegisterShare` |
| comment-service                | —                              | `PostService.CheckPostExists`, `CommentRecommendationService.RankComments`                                                                |
| post-interaction-service       | `PostInteractionService`       | `PostService.CheckPostExists`                                                                                                             |
| bookmark-service               | `BookmarkService`              | `PostService.FindPostById` (denormalise)                                                                                                  |
| post-recommendation-service    | `PostRecommendationService`    | — (reads Redis/Qdrant/Postgres internally)                                                                                                |
| comment-recommendation-service | `CommentRecommendationService` | —                                                                                                                                         |
| notification-service           | —                              | `UserService`                                                                                                                             |
| search-service                 | —                              | —                                                                                                                                         |
| group-service                  | `GroupService`                 | `UserService`                                                                                                                             |

---

## Client call patterns

### post-service → PostRecommendationService.RankPosts (feed enrichment)

```java
// PostFeedService.java

@GrpcClient("post-recommendation-service")
private ReactorPostRecommendationServiceGrpc.ReactorPostRecommendationServiceStub recommendationStub;

@GrpcClient("post-interaction-service")
private ReactorPostInteractionServiceGrpc.ReactorPostInteractionServiceStub interactionStub;

public Mono<List<PostDto>> buildHomeFeed(String userId, String cursor, int size) {
    // Bước 1: lấy 3× candidates từ Postgres
    return postRepository.findCandidates(userId, cursor, size * 3)
        .collectList()
        .flatMap(candidates -> {
            List<String> ids = candidates.stream().map(p -> p.getId().toString()).toList();

            // Bước 2: batch load counts (parallel với recommendation để tiết kiệm thời gian)
            Mono<Map<String, PostCountsResponse>> countsMono = interactionStub
                .batchGetPostCounts(Mono.just(
                    BatchGetPostCountsRequest.newBuilder().addAllPostIds(ids).build()
                ))
                .map(BatchPostCountsResponse::getCountsMap)
                .timeout(Duration.ofMillis(300))
                .onErrorReturn(Map.of());

            // Bước 3: recommendation ranking
            Mono<List<String>> rankedIdsMono = countsMono.flatMap(counts -> {
                List<CandidatePost> candidateProtos = candidates.stream()
                    .map(p -> {
                        PostCountsResponse c = counts.getOrDefault(p.getId().toString(),
                            PostCountsResponse.getDefaultInstance());
                        return CandidatePost.newBuilder()
                            .setPostId(p.getId().toString())
                            .setAuthorId(p.getAuthorId().toString())
                            .setCreatedAtMs(p.getCreatedAt().toEpochMilli())
                            .setLikeRaw(c.getLikeRaw())
                            .setShareRaw(c.getShareRaw())
                            .setViewRaw(c.getViewRaw())
                            .build();
                    }).toList();

                return recommendationStub
                    .rankPosts(Mono.just(
                        RankPostsRequest.newBuilder()
                            .setUserId(userId)
                            .addAllCandidates(candidateProtos)
                            .setReturnSize(size)
                            .setFeedType("HOME")
                            .build()
                    ))
                    .map(RankPostsResponse::getRankedPostIdsList)
                    .timeout(Duration.ofMillis(400))
                    // Fallback: nếu recommendation timeout → chronological order
                    .onErrorReturn(candidates.stream()
                        .limit(size)
                        .map(p -> p.getId().toString())
                        .toList()
                    );
            });

            // Bước 4: fetch full post data theo thứ tự ranked
            return Mono.zip(rankedIdsMono, countsMono)
                .flatMap(tuple -> enrichAndSort(tuple.getT1(), tuple.getT2(), candidates));
        });
}
```

### comment-service → CommentRecommendationService.RankComments

```java
// CommentQueryService.java

@GrpcClient("comment-recommendation-service")
private ReactorCommentRecommendationServiceGrpc.ReactorCommentRecommendationServiceStub recStub;

public Mono<List<CommentDto>> getBestComments(String postId, String userId, int size) {
    // Lấy 3× candidates từ Cassandra
    return commentRepository.findTopCandidates(postId, size * 3)
        .collectList()
        .flatMap(candidates -> {
            List<CandidateComment> protos = candidates.stream()
                .map(c -> CandidateComment.newBuilder()
                    .setCommentId(c.getCommentId().toString())
                    .setAuthorId(c.getAuthorId().toString())
                    .setReactionCount(c.getReactionCount())
                    .setReplyCount(c.getReplyCount())
                    .setViewCount(c.getViewCount())
                    .setDepth(c.getDepth())
                    .setContentLength(c.getContent().length())
                    .build())
                .toList();

            return recStub.rankComments(Mono.just(
                    RankCommentsRequest.newBuilder()
                        .setPostId(postId)
                        .setUserId(userId)
                        .addAllCandidates(protos)
                        .setReturnSize(size)
                        .build()
                ))
                .map(RankCommentsResponse::getRankedCommentIdsList)
                .timeout(Duration.ofMillis(350))
                // Fallback: reaction_count DESC
                .onErrorReturn(candidates.stream()
                    .sorted(Comparator.comparingInt(CommentEntity::getReactionCount).reversed())
                    .limit(size)
                    .map(c -> c.getCommentId().toString())
                    .toList()
                )
                .flatMap(rankedIds -> fetchAndSortComments(rankedIds, candidates));
        });
}
```

### post-service → PostInteractionService.RegisterShare (fire-and-forget)

```java
// RepostService.java

public Mono<Post> repost(String originalPostId, String actorId) {
    return postRepository.save(buildRepostRecord(originalPostId, actorId))
        .flatMap(savedPost -> {
            interactionStub.registerShare(Mono.just(
                    RegisterShareRequest.newBuilder()
                        .setPostId(originalPostId)
                        .setActorId(actorId)
                        .setSharedPostId(savedPost.getId().toString())
                        .build()
                ))
                .timeout(Duration.ofMillis(500))
                .onErrorResume(e -> {
                    log.warn("RegisterShare failed for {}: {}", originalPostId, e.getMessage());
                    return Mono.empty();
                })
                .subscribe();  // fire-and-forget

            return Mono.just(savedPost);
        });
}
```
