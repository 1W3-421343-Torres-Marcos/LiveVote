package com.livevote.results.controller;

import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.service.ResultsProjectionService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-blocking WebFlux endpoints offloading JDBC/JPA reads to boundedElastic.
 */
@RestController
@RequestMapping("/api/results")
public class ResultsController {

    private final ResultsProjectionService projectionService;

    public ResultsController(ResultsProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @GetMapping("/{id}")
    public Mono<PollResultsResponse> getResults(@PathVariable UUID id) {
        return Mono.fromCallable(() -> projectionService.getResults(id))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
