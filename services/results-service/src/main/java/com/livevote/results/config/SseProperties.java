package com.livevote.results.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Server-Sent Events streaming and heartbeats.
 */
@ConfigurationProperties(prefix = "app.sse")
public class SseProperties {

    private long heartbeatIntervalMs = 20000;
    private int clientBufferSize = 256;

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public int getClientBufferSize() {
        return clientBufferSize;
    }

    public void setClientBufferSize(int clientBufferSize) {
        this.clientBufferSize = clientBufferSize;
    }
}
