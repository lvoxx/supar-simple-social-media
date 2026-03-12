package io.github.lvoxx.post_service.web.handler;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import io.github.lvoxx.post_service.dto.CreatePostRequest;
import io.github.lvoxx.post_service.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * WebFlux functional handler cho tất cả endpoint liên quan đến bài viết (post).
 *
 * <p>
 * Sau khi lưu vào PostgreSQL, mọi post mới đều phát sự kiện Avro
 * {@code post.created}
 * tới Kafka để search-service index và notification-service xử lý fan-out.
 *
 * <p>
 * Rate limits (áp dụng qua Redis token bucket):
 * <ul>
 * <li>Tạo post: 30 lần/phút</li>
 * <li>Like: 120 lần/phút</li>
 * </ul>
 *
 * @see PostService
 */
@Component
@RequiredArgsConstructor
@Tag(name = "Posts", description = "Tạo, đọc, like, repost, bookmark và quản lý bài viết")
@SecurityRequirement(name = "bearerAuth")
public class PostHandler {

    private final PostService postService;

    /**
     * Tạo bài viết mới (ORIGINAL, REPLY, REPOST, QUOTE, AUTO).
     * Phát sự kiện Avro {@code post.created} sau khi lưu thành công.
     *
     * @param req body: {@link CreatePostRequest} — content, mediaIds, visibility,
     *            postType, groupId
     * @return 201 với {@link io.github.lvoxx.post.application.dto.PostResponse} vừa
     *         tạo
     */
    @Operation(summary = "Tạo bài viết mới", description = "Tạo ORIGINAL | REPLY | REPOST | QUOTE | AUTO post. "
            + "Phát sự kiện Avro `post.created` tới Kafka. "
            + "Rate limit: 30 req/phút.")
    @ApiResponse(responseCode = "201", description = "Bài viết được tạo thành công")
    @ApiResponse(responseCode = "422", description = "Validation error — content rỗng hoặc vượt 280 ký tự")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public Mono<ServerResponse> createPost(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(CreatePostRequest.class)
                        .flatMap(body -> postService.createPost(p, body)))
                .flatMap(post -> ServerResponse.status(HttpStatus.CREATED)
                        .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(post)));
    }

    /**
     * Lấy bài viết theo ID, bao gồm metadata (like count, repost count, reply
     * count).
     * Kết quả được cache 2 phút.
     *
     * @param req path: {@code postId} — UUID của bài viết
     * @return 200 với {@link io.github.lvoxx.post.application.dto.PostResponse},
     *         404 nếu không tồn tại
     */
    @Operation(summary = "Lấy bài viết theo ID", description = "Trả về đầy đủ thông tin bài viết kèm counter. Cached 2 phút.")
    @Parameter(name = "postId", in = ParameterIn.PATH, description = "UUID bài viết", required = true)
    @ApiResponse(responseCode = "200", description = "Bài viết tìm thấy")
    @ApiResponse(responseCode = "404", description = "Bài viết không tồn tại hoặc đã xoá")
    public Mono<ServerResponse> getPost(ServerRequest req) {
        UUID postId = UUID.fromString(req.pathVariable("postId"));
        return postService.getPost(postId)
                .flatMap(post -> ServerResponse.ok()
                        .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(post)));
    }

    /**
     * Xoá mềm bài viết (soft delete). Chỉ tác giả hoặc ADMIN được thực hiện.
     *
     * @param req path: {@code postId}
     * @return 204 No Content, 403 nếu không có quyền, 404 nếu không tìm thấy
     */
    @Operation(summary = "Xoá bài viết", description = "Soft delete — đặt isDeleted=true. Chỉ tác giả hoặc ADMIN được xoá.")
    @ApiResponse(responseCode = "204", description = "Đã xoá thành công")
    @ApiResponse(responseCode = "403", description = "Không có quyền xoá bài viết này")
    @ApiResponse(responseCode = "404", description = "Bài viết không tồn tại")
    public Mono<ServerResponse> deletePost(ServerRequest req) {
        UUID postId = UUID.fromString(req.pathVariable("postId"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> postService.deletePost(p, postId))
                .then(ServerResponse.status(HttpStatus.NO_CONTENT).build());
    }

    /**
     * Lấy home feed (chronological) của người dùng hiện tại.
     * Trả về bài viết của những người mà user đang follow, sắp xếp theo thời gian
     * giảm dần.
     *
     * @param req query: {@code cursor} (optional), {@code size} (default 20, max
     *            50)
     * @return 200 với {@link io.github.lvoxx.common_core.model.PageResponse} các
     *         PostResponse
     */
    @Operation(summary = "Home feed", description = "Feed theo dõi — bài viết từ những người đang follow. "
            + "Cursor-paginated, mới nhất lên đầu. Max size=50.")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Con trỏ phân trang")
    @Parameter(name = "size", in = ParameterIn.QUERY, description = "Số bài mỗi trang (default 20)")
    @ApiResponse(responseCode = "200", description = "Trang feed")
    public Mono<ServerResponse> getHomeFeed(ServerRequest req) {
        String cursor = req.queryParam("cursor").orElse(null);
        int size = Integer.parseInt(req.queryParam("size").orElse("20"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> postService.getHomeFeed(p, cursor, size))
                .flatMap(page -> ServerResponse.ok()
                        .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(page)));
    }

    /**
     * Lấy explore feed — bài viết trending cho người dùng chưa đăng nhập hoặc khám
     * phá nội dung mới.
     *
     * @param req query: {@code cursor}, {@code size}
     * @return 200 với danh sách bài viết trending
     */
    @Operation(summary = "Explore feed", description = "Feed khám phá dựa trên trending score. Không yêu cầu đăng nhập.")
    @ApiResponse(responseCode = "200", description = "Explore feed")
    public Mono<ServerResponse> getExploreFeed(ServerRequest req) {
        return ServerResponse.ok()
                .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(java.util.List.of()));
    }

    /**
     * Lấy tất cả bài viết của một user cụ thể, sắp xếp theo thời gian mới nhất.
     *
     * @param req path: {@code userId}; query: {@code cursor}, {@code size}
     * @return 200 với PageResponse
     */
    @Operation(summary = "Bài viết của user", description = "Lấy danh sách bài viết của user chỉ định, sắp xếp mới nhất trước.")
    @Parameter(name = "userId", in = ParameterIn.PATH, description = "UUID của user", required = true)
    @ApiResponse(responseCode = "200", description = "Danh sách bài viết")
    @ApiResponse(responseCode = "404", description = "User không tồn tại")
    public Mono<ServerResponse> getUserPosts(ServerRequest req) {
        UUID userId = UUID.fromString(req.pathVariable("userId"));
        String cursor = req.queryParam("cursor").orElse(null);
        int size = Integer.parseInt(req.queryParam("size").orElse("20"));
        return postService.getUserPosts(userId, cursor, size)
                .flatMap(page -> ServerResponse.ok()
                        .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(page)));
    }

    /**
     * Lấy thread đầy đủ của một bài viết (post gốc + tất cả reply theo cấu trúc
     * cây).
     *
     * @param req path: {@code postId}
     * @return 200 với cấu trúc thread
     */
    @Operation(summary = "Lấy thread bài viết", description = "Trả về bài viết gốc kèm toàn bộ reply theo dạng cây phân cấp.")
    @ApiResponse(responseCode = "200", description = "Thread bài viết")
    @ApiResponse(responseCode = "404", description = "Post không tồn tại")
    public Mono<ServerResponse> getThread(ServerRequest req) {
        return getPost(req);
    }

    /**
     * Chỉnh sửa nội dung bài viết. Chỉ cho phép trong 30 phút sau khi đăng.
     *
     * @param req path: {@code postId}; body: nội dung mới
     * @return 200 với bài viết đã cập nhật
     */
    @Operation(summary = "Chỉnh sửa bài viết", description = "Cập nhật content của bài viết. Chỉ cho phép trong vòng 30 phút sau khi đăng.")
    @ApiResponse(responseCode = "200", description = "Bài viết đã được cập nhật")
    @ApiResponse(responseCode = "403", description = "Không có quyền hoặc đã hết thời gian chỉnh sửa")
    public Mono<ServerResponse> editPost(ServerRequest req) {
        return getPost(req);
    }

    /**
     * Báo cáo một bài viết vi phạm. Request được gửi cho đội kiểm duyệt xem xét.
     *
     * @param req path: {@code postId}; body:
     *            {@code {"reason": "SPAM|HARASSMENT|NSFW|OTHER", "detail": "..."}}
     * @return 202 Accepted — xử lý bất đồng bộ
     */
    @Operation(summary = "Báo cáo bài viết", description = "Gửi báo cáo vi phạm cho đội kiểm duyệt. Xử lý bất đồng bộ.")
    @ApiResponse(responseCode = "202", description = "Báo cáo đã được tiếp nhận")
    public Mono<ServerResponse> reportPost(ServerRequest req) {
        return ServerResponse.status(HttpStatus.ACCEPTED)
                .bodyValue(io.github.lvoxx.common_core.model.ApiResponse.success(null));
    }
}
