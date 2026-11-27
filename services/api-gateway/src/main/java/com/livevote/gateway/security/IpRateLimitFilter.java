package com.livevote.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.livevote.gateway.config.RateLimitProperties;
import com.livevote.gateway.exception.GatewayErrorWriter;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bounded per-IP token bucket rate limiter for registration, login, and anonymous voting endpoints.
 */
@Component
public class IpRateLimitFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -200;
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern VOTE_PATH = Pattern.compile("^/api/polls/[^/]+/votes/?$");

    private final RateLimitProperties properties;
    private final GatewayErrorWriter errorWriter;
    private final LongSupplier nanoTimeSupplier;
    private final Cache<String, TokenBucket> buckets;

    @Autowired
    public IpRateLimitFilter(RateLimitProperties properties, GatewayErrorWriter errorWriter) {
        this(properties, errorWriter, System::nanoTime);
    }

    IpRateLimitFilter(
            RateLimitProperties properties,
            GatewayErrorWriter errorWriter,
            LongSupplier nanoTimeSupplier
    ) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.nanoTimeSupplier = nanoTimeSupplier;
        long ttlSeconds = Math.max(properties.getAuthWindowSeconds(), properties.getVoteWindowSeconds()) * 2L;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.getMaxTrackedIps())
                .expireAfterAccess(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        BucketRule rule = resolveRule(request.getMethod(), request.getPath().value());
        if (rule == null) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(request);
        String key = rule.name() + ":" + clientIp;
        long now = nanoTimeSupplier.getAsLong();

        TokenBucket bucket = buckets.get(key, k -> new TokenBucket(rule.capacity(), rule.windowSeconds(), now));
        long retryAfterSeconds = bucket.tryConsume(now);
        if (retryAfterSeconds > 0L) {
            return errorWriter.writeError(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Try again later.",
                    Map.of(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
            );
        }

        return chain.filter(exchange);
    }

    private BucketRule resolveRule(HttpMethod method, String path) {
        if (!HttpMethod.POST.equals(method)) {
            return null;
        }
        if ("/api/auth/register".equals(path) || "/api/auth/login".equals(path)) {
            return new BucketRule("auth", properties.getAuthCapacity(), properties.getAuthWindowSeconds());
        }
        if (VOTE_PATH.matcher(path).matches()) {
            return new BucketRule("vote", properties.getVoteCapacity(), properties.getVoteWindowSeconds());
        }
        return null;
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst(HEADER_X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String firstIp = forwardedFor.split(",")[0].trim();
            if (!firstIp.isEmpty()) {
                return firstIp;
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            if (remoteAddress.getAddress() != null) {
                return remoteAddress.getAddress().getHostAddress();
            }
            return remoteAddress.getHostString();
        }
        return "unknown";
    }

    private record BucketRule(String name, int capacity, long windowSeconds) {
    }

    private static final class TokenBucket {

        private final int capacity;
        private final double refillTokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, long windowSeconds, long nowNanos) {
            this.capacity = capacity;
            this.refillTokensPerNano = (double) capacity / (windowSeconds * 1_000_000_000.0d);
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        synchronized long tryConsume(long nowNanos) {
            long elapsed = Math.max(0L, nowNanos - lastRefillNanos);
            if (elapsed > 0L) {
                tokens = Math.min(capacity, tokens + elapsed * refillTokensPerNano);
                lastRefillNanos = nowNanos;
            }
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return 0L;
            }
            double missingTokens = 1.0d - tokens;
            long retryAfter = (long) Math.ceil((missingTokens / refillTokensPerNano) / 1_000_000_000.0d);
            return Math.max(1L, retryAfter);
        }
    }
}
