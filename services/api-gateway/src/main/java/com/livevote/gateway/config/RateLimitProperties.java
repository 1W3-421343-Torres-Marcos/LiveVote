package com.livevote.gateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-IP token bucket limits for public auth and voting endpoints.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private int authCapacity = 10;
    private long authWindowSeconds = 60;
    private int voteCapacity = 30;
    private long voteWindowSeconds = 60;
    private long maxTrackedIps = 10_000;

    @PostConstruct
    void validate() {
        if (authCapacity <= 0 || authWindowSeconds <= 0) {
            throw new IllegalStateException("Auth rate limit capacity and window must be positive");
        }
        if (voteCapacity <= 0 || voteWindowSeconds <= 0) {
            throw new IllegalStateException("Vote rate limit capacity and window must be positive");
        }
        if (maxTrackedIps <= 0) {
            throw new IllegalStateException("app.rate-limit.max-tracked-ips must be positive");
        }
    }

    public int getAuthCapacity() {
        return authCapacity;
    }

    public void setAuthCapacity(int authCapacity) {
        this.authCapacity = authCapacity;
    }

    public long getAuthWindowSeconds() {
        return authWindowSeconds;
    }

    public void setAuthWindowSeconds(long authWindowSeconds) {
        this.authWindowSeconds = authWindowSeconds;
    }

    public int getVoteCapacity() {
        return voteCapacity;
    }

    public void setVoteCapacity(int voteCapacity) {
        this.voteCapacity = voteCapacity;
    }

    public long getVoteWindowSeconds() {
        return voteWindowSeconds;
    }

    public void setVoteWindowSeconds(long voteWindowSeconds) {
        this.voteWindowSeconds = voteWindowSeconds;
    }

    public long getMaxTrackedIps() {
        return maxTrackedIps;
    }

    public void setMaxTrackedIps(long maxTrackedIps) {
        this.maxTrackedIps = maxTrackedIps;
    }
}
