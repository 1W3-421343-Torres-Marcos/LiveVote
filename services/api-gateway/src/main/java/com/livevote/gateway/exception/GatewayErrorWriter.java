package com.livevote.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Serializes uniform JSON error payloads from reactive Gateway global filters.
 */
@Component
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        return writeError(exchange, status, message, Map.of());
    }

    public Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String message,
            Map<String, String> extraHeaders
    ) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        extraHeaders.forEach((name, value) -> response.getHeaders().set(name, value));

        ApiErrorResponse payload = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                exchange.getRequest().getPath().value()
        );

        byte[] body = serialize(payload);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serialize(ApiErrorResponse payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            String fallback = "{\"status\":" + payload.status() + ",\"message\":\"" + payload.message() + "\"}";
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }
}
