package com.livevote.votes.security;

import com.livevote.votes.config.SecurityProperties;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves presenter authentication from either validated JWT or trusted Gateway X-User-Id header.
 */
@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityProperties securityProperties;
    private final JwtService jwtService;

    public AuthenticationFilter(SecurityProperties securityProperties, JwtService jwtService) {
        this.securityProperties = securityProperties;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Optional<UUID> userId = securityProperties.isTrustGatewayHeader()
                ? resolveFromGatewayHeader(request)
                : resolveFromBearerToken(request);

        if (userId.isPresent()) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId.get(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_PRESENTER"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private Optional<UUID> resolveFromGatewayHeader(HttpServletRequest request) {
        String headerValue = request.getHeader(HEADER_USER_ID);
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(headerValue.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<UUID> resolveFromBearerToken(HttpServletRequest request) {
        // Explicitly ignore X-User-Id when running without a trusted Gateway in front.
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jwtService.extractUserId(token));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
