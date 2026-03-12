package io.github.lvoxx.media_service.web.router;

import static org.springframework.web.reactive.function.server.RouterFunctions.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_keys.RouterPaths;
import io.github.lvoxx.media_service.web.handler.MediaHandler;

@Configuration
public class MediaRouter {
    @Bean
    public RouterFunction<ServerResponse> mediaRoutes(MediaHandler h) {
        return route()
                .POST(RouterPaths.MediaService.UPLOAD, h::upload)
                .GET(RouterPaths.MediaService.GET_BY_ID, h::getById)
                .GET(RouterPaths.MediaService.GET_STATUS, h::getStatus)
                .DELETE(RouterPaths.MediaService.DELETE, h::delete)
                .build();
    }
}
