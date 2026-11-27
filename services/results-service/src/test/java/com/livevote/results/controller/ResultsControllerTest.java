package com.livevote.results.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.livevote.results.dto.HeartbeatEvent;
import com.livevote.results.dto.OptionResultResponse;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.entity.PollProjectionStatus;
import com.livevote.results.exception.ResultsNotFoundException;
import com.livevote.results.service.ResultsProjectionService;
import com.livevote.results.service.ResultsStreamService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@WebFluxTest(controllers = ResultsController.class)
class ResultsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ResultsProjectionService projectionService;
    @MockitoBean
    private ResultsStreamService resultsStreamService;

    @Test
    void shouldReturnPollResultsWhenFound() {
        UUID pollId = UUID.randomUUID();
        PollResultsResponse response = new PollResultsResponse(
                pollId,
                "Best cloud?",
                PollProjectionStatus.OPEN,
                3L,
                List.of(new OptionResultResponse(UUID.randomUUID(), "AWS", 0, 3L)),
                Instant.now(),
                null
        );
        when(projectionService.getResults(pollId)).thenReturn(response);

        webTestClient.get()
                .uri("/api/results/{id}", pollId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pollId").isEqualTo(pollId.toString())
                .jsonPath("$.totalVotes").isEqualTo(3)
                .jsonPath("$.options[0].votes").isEqualTo(3);
    }

    @Test
    void shouldReturn404WhenPollMissingAnd400OnInvalidUuid() {
        UUID missingId = UUID.randomUUID();
        when(projectionService.getResults(missingId))
                .thenThrow(new ResultsNotFoundException("Poll results not found"));

        webTestClient.get()
                .uri("/api/results/{id}", missingId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);

        webTestClient.get()
                .uri("/api/results/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void shouldStreamSseEventsWithNoBufferingHeaders() {
        UUID pollId = UUID.randomUUID();
        PollResultsResponse snapshot = new PollResultsResponse(
                pollId,
                "Best language?",
                PollProjectionStatus.OPEN,
                2L,
                List.of(new OptionResultResponse(UUID.randomUUID(), "Java", 0, 2L)),
                Instant.now(),
                null
        );
        when(resultsStreamService.streamResults(pollId)).thenReturn(Flux.just(
                ServerSentEvent.<Object>builder(snapshot).event("results").build(),
                ServerSentEvent.<Object>builder(new HeartbeatEvent(Instant.now())).event("heartbeat").build()
        ));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.get()
                .uri("/api/results/{id}/stream", pollId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-cache"))
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

        StepVerifier.create(result.getResponseBody())
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("results");
                    assertThat(event.data()).contains("\"totalVotes\":2");
                })
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("heartbeat");
                    assertThat(event.data()).contains("\"timestamp\"");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnJson404And400OnStreamErrorsEvenWhenAcceptingEventStream() {
        UUID missingId = UUID.randomUUID();
        when(resultsStreamService.streamResults(missingId))
                .thenReturn(Flux.error(new ResultsNotFoundException("Poll results not found")));

        webTestClient.get()
                .uri("/api/results/{id}/stream", missingId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);

        webTestClient.get()
                .uri("/api/results/not-a-uuid/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }
}
