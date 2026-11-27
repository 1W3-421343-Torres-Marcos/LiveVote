package com.livevote.results.controller;

import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.service.ResultsProjectionService;
import com.livevote.results.service.ResultsStreamService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-blocking WebFlux endpoints offloading JDBC/JPA reads to boundedElastic.
 */
@RestController
@RequestMapping("/api/results")
public class ResultsController {

    private final ResultsProjectionService projectionService;
    private final ResultsStreamService resultsStreamService;

    public ResultsController(
            ResultsProjectionService projectionService,
            ResultsStreamService resultsStreamService
    ) {
        this.projectionService = projectionService;
        this.resultsStreamService = resultsStreamService;
    }

    @GetMapping("/{id}")
    public Mono<PollResultsResponse> getResults(@PathVariable UUID id) {
        return Mono.fromCallable(() -> projectionService.getResults(id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamResults(
            @PathVariable UUID id,
            ServerHttpResponse response
    ) {
        return resultsStreamService.streamResults(id)
                .doOnSubscribe(subscription -> {
                    response.getHeaders().setCacheControl(CacheControl.noCache());
                    response.getHeaders().set("X-Accel-Buffering", "no");
                });
    }
}
