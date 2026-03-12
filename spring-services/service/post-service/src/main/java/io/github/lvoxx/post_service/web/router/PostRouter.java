package io.github.lvoxx.post_service.web.router;

import static org.springframework.web.reactive.function.server.RouterFunctions.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_keys.RouterPaths;
import io.github.lvoxx.post_service.web.handler.PostHandler;

@Configuration
public class PostRouter {

    @Bean
    public RouterFunction<ServerResponse> postRoutes(PostHandler h) {
        return route()
                .GET(RouterPaths.PostService.FEED_HOME, h::getHomeFeed)
                .GET(RouterPaths.PostService.FEED_EXPLORE, h::getExploreFeed)
                .GET(RouterPaths.PostService.USER_POSTS, h::getUserPosts)
                .GET(RouterPaths.PostService.THREAD, h::getThread)
                .GET(RouterPaths.PostService.POST, h::getPost)
                .POST(RouterPaths.PostService.CREATE_POST, h::createPost)
                .PUT(RouterPaths.PostService.POST, h::editPost)
                .DELETE(RouterPaths.PostService.POST, h::deletePost)
                .POST(RouterPaths.PostService.REPORT_POST, h::reportPost)
                .build();
    }
}
