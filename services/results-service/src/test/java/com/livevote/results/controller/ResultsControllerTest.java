package com.livevote.results.controller;

import static org.mockito.Mockito.when;

import com.livevote.results.dto.OptionResultResponse;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.entity.PollProjectionStatus;
import com.livevote.results.exception.ResultsNotFoundException;
import com.livevote.results.service.ResultsProjectionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = ResultsController.class)
class ResultsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ResultsProjectionService projectionService;

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
}
