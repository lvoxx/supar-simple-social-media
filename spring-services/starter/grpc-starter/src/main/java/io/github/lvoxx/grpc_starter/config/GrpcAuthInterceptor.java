package io.github.lvoxx.grpc_starter.config;

import io.grpc.*;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.common_core.enums.UserRole;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@GrpcGlobalServerInterceptor
public class GrpcAuthInterceptor implements ServerInterceptor {

    public static final Context.Key<UserPrincipal> USER_PRINCIPAL_KEY =
            Context.key("userPrincipal");

    private static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ROLES_KEY =
            Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> FORWARDED_FOR_KEY =
            Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String userIdStr = headers.get(USER_ID_KEY);
        String rolesStr = headers.get(USER_ROLES_KEY);
        String ip = headers.get(FORWARDED_FOR_KEY);

        Context ctx = Context.current();
        if (userIdStr != null) {
            try {
                UUID userId = UUID.fromString(userIdStr);
                Set<UserRole> roles = rolesStr != null
                        ? Arrays.stream(rolesStr.split(","))
                                .map(String::trim)
                                .map(UserRole::valueOf)
                                .collect(Collectors.toSet())
                        : Set.of();
                UserPrincipal principal = new UserPrincipal(userId, null, roles, ip);
                ctx = ctx.withValue(USER_PRINCIPAL_KEY, principal);
            } catch (Exception e) {
                log.warn("Failed to parse gRPC auth headers: {}", e.getMessage());
            }
        }

        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
