package com.livevote.votes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security mode settings controlling whether Gateway identity headers are trusted.
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private boolean trustGatewayHeader = false;

    public boolean isTrustGatewayHeader() {
        return trustGatewayHeader;
    }

    public void setTrustGatewayHeader(boolean trustGatewayHeader) {
        this.trustGatewayHeader = trustGatewayHeader;
    }
}
