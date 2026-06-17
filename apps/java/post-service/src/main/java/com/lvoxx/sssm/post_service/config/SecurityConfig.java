package com.lvoxx.sssm.post_service.config;

import com.lvoxx.sssm.post_service.security.GatewayAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless authorization. Token validation happens in the gateway sidecar, NOT here (see
 * {@code docs/adr/0003-gateway-sidecar-authn.md}): this service never decodes a JWT.
 * {@link GatewayAuthenticationFilter} turns the gateway-forwarded {@code X-Auth-*} headers into the
 * security context.
 *
 * <p>Post reads are public; creating and deleting posts require an authenticated caller. A
 * missing/invalid identity on a protected route yields 401 (not a redirect), as befits a headless
 * API.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/**")
                                .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new GatewayAuthenticationFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
