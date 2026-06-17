package com.lvoxx.sssm.post_service.web;

import com.lvoxx.sssm.post_service.domain.Post;
import com.lvoxx.sssm.post_service.security.AuthenticatedUser;
import com.lvoxx.sssm.post_service.service.CursorPage;
import com.lvoxx.sssm.post_service.service.PostService;
import com.lvoxx.sssm.post_service.web.dto.CreatePostRequest;
import com.lvoxx.sssm.post_service.web.dto.PageResponse;
import com.lvoxx.sssm.post_service.web.dto.PostResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post / thread CRUD. Reads (a single post, an author's posts, a post's replies) are public;
 * creating and deleting act as the authenticated caller (gateway-forwarded identity).
 */
@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreatePostRequest req) {
        return PostResponse.from(posts.create(user.id(), req));
    }

    @GetMapping("/{id}")
    public PostResponse byId(@PathVariable UUID id) {
        return PostResponse.from(posts.getById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        posts.delete(user.id(), id);
    }

    @GetMapping("/{id}/replies")
    public PageResponse<PostResponse> replies(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return toResponse(posts.listReplies(id, cursor, limit));
    }

    @GetMapping
    public PageResponse<PostResponse> byAuthor(
            @RequestParam UUID authorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return toResponse(posts.listByAuthor(authorId, cursor, limit));
    }

    private static PageResponse<PostResponse> toResponse(CursorPage<Post> page) {
        return new PageResponse<>(
                page.items().stream().map(PostResponse::from).toList(),
                page.nextCursor());
    }
}
