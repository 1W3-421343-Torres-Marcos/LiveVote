package com.livevote.results.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging retry and DLQ thresholds for AMQP event consumption.
 */
@ConfigurationProperties(prefix = "app.messaging")
public class MessagingProperties {

    private long retryDelayMs = 5000;
    private int maxPendingRetries = 5;
    private int localRetryMaxAttempts = 3;
    private long localRetryInitialMs = 500;

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public int getMaxPendingRetries() {
        return maxPendingRetries;
    }

    public void setMaxPendingRetries(int maxPendingRetries) {
        this.maxPendingRetries = maxPendingRetries;
    }

    public int getLocalRetryMaxAttempts() {
        return localRetryMaxAttempts;
    }

    public void setLocalRetryMaxAttempts(int localRetryMaxAttempts) {
        this.localRetryMaxAttempts = localRetryMaxAttempts;
    }

    public long getLocalRetryInitialMs() {
        return localRetryInitialMs;
    }

    public void setLocalRetryInitialMs(long localRetryInitialMs) {
        this.localRetryInitialMs = localRetryInitialMs;
    }
}
