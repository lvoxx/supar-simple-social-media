package com.lvoxx.sssm.post.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the security context from identity headers injected by the gateway sidecar. The
 * sidecar has already validated the Keycloak access token; this service trusts the headers because
 * the network topology guarantees the service is only reachable through the gateway.
 *
 * <p>If {@code X-Auth-Subject} is absent or malformed the request continues unauthenticated, and
 * the authorization rules in {@code SecurityConfig} decide whether that is allowed (public reads)
 * or rejected with 401 (protected routes).
 */
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    public static final String SUBJECT_HEADER = "X-Auth-Subject";
    public static final String USERNAME_HEADER = "X-Auth-Username";
    public static final String ROLES_HEADER = "X-Auth-Roles";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String subject = request.getHeader(SUBJECT_HEADER);
        if (subject != null && !subject.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, subject.trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String subject) {
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException malformed) {
            // A bad subject header means a misconfigured gateway, not a valid caller — stay
            // unauthenticated rather than trusting garbage.
            return;
        }

        List<String> roles = parseRoles(request.getHeader(ROLES_HEADER));
        AuthenticatedUser principal =
                new AuthenticatedUser(id, request.getHeader(USERNAME_HEADER), roles);
        List<GrantedAuthority> authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();

        var authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static List<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (String role : Arrays.asList(header.split(","))) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles;
    }
}
