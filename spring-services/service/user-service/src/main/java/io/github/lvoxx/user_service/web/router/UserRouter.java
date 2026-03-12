package io.github.lvoxx.user_service.web.router;

import static org.springframework.web.reactive.function.server.RouterFunctions.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_keys.RouterPaths;
import io.github.lvoxx.user_service.web.handler.UserHandler;

@Configuration
public class UserRouter {

    @Bean
    public RouterFunction<ServerResponse> userRoutes(UserHandler h) {
        return route()
                .GET(RouterPaths.UserService.ABOUTME, h::getMe)
                .GET(RouterPaths.UserService.SEARCH, h::searchUsers)
                .GET(RouterPaths.UserService.HISTORY, h::getHistory)
                .GET(RouterPaths.UserService.FOLLOW_REQUESTS, h::getFollowRequests)
                .GET(RouterPaths.UserService.BY_USERNAME, h::getByUsername)
                .GET(RouterPaths.UserService.FOLLOWERS, h::getFollowers)
                .GET(RouterPaths.UserService.FOLLOWING, h::getFollowing)
                .PUT(RouterPaths.UserService.ABOUTME, h::updateProfile)
                .PUT(RouterPaths.UserService.UPDATE_AVATAR, h::updateAvatar)
                .PUT(RouterPaths.UserService.UPDATE_BACKGROUND, h::updateBackground)
                .PUT(RouterPaths.UserService.UPDATE_SETTINGS, h::updateSettings)
                .PUT(RouterPaths.UserService.RESPOND_FOLLOW_REQUEST, h::respondFollowRequest)
                .POST(RouterPaths.UserService.SUBMIT_VERIFICATION, h::submitVerification)
                .POST(RouterPaths.UserService.FOLLOW, h::follow)
                .DELETE(RouterPaths.UserService.FOLLOW, h::unfollow)
                .build();
    }
}
