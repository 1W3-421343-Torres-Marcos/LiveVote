package com.livevote.results.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.livevote.results.dto.HeartbeatEvent;
import com.livevote.results.dto.OptionResultResponse;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.entity.PollProjectionStatus;
import com.livevote.results.exception.ResultsNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

@ExtendWith(MockitoExtension.class)
class ResultsStreamServiceTest {

    @Mock
    private ResultsProjectionService projectionService;

    private VirtualTimeScheduler virtualScheduler;
    private ResultsStreamService streamService;

    @BeforeEach
    void setUp() {
        virtualScheduler = VirtualTimeScheduler.create();
        streamService = new ResultsStreamService(projectionService, Duration.ofSeconds(20), 256, virtualScheduler);
    }

    @AfterEach
    void tearDown() {
        virtualScheduler.dispose();
    }

    @Test
    void shouldEmitInitialSnapshotLiveUpdatesAndHeartbeatsAt20sIntervals() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollResultsResponse snapshot = results(pollId, optionId, "SSE live test", 0L);
        PollResultsResponse vote1 = results(pollId, optionId, "SSE live test", 1L);
        when(projectionService.getResults(pollId)).thenReturn(snapshot);

        StepVerifier.create(streamService.streamResults(pollId))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("results");
                    assertThat(event.retry()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(event.data()).isEqualTo(snapshot);
                })
                .then(() -> streamService.publishUpdate(vote1))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("results");
                    assertThat(event.data()).isEqualTo(vote1);
                })
                .then(() -> virtualScheduler.advanceTimeBy(Duration.ofSeconds(20)))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("heartbeat");
                    assertThat(event.data()).isInstanceOf(HeartbeatEvent.class);
                })
                .then(() -> virtualScheduler.advanceTimeBy(Duration.ofSeconds(20)))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("heartbeat");
                    assertThat(event.data()).isInstanceOf(HeartbeatEvent.class);
                })
                .thenCancel()
                .verify();

        assertThat(streamService.activeSubscriberCount(pollId)).isZero();
        assertThat(streamService.activePollChannelCount()).isZero();
    }

    @Test
    void shouldBroadcastToMultipleSubscribersAndCleanUpOnlyAfterLastCancels() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollResultsResponse snapshot = results(pollId, optionId, "Multi sub", 0L);
        PollResultsResponse update1 = results(pollId, optionId, "Multi sub", 1L);
        PollResultsResponse update2 = results(pollId, optionId, "Multi sub", 2L);
        when(projectionService.getResults(pollId)).thenReturn(snapshot);

        List<ServerSentEvent<Object>> receivedByFirst = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<ServerSentEvent<Object>> receivedBySecond = new java.util.concurrent.CopyOnWriteArrayList<>();

        Disposable sub1 = streamService.streamResults(pollId).subscribe(receivedByFirst::add);
        Disposable sub2 = streamService.streamResults(pollId).subscribe(receivedBySecond::add);

        awaitCondition(() -> receivedByFirst.size() == 1 && receivedBySecond.size() == 1);
        assertThat(streamService.activeSubscriberCount(pollId)).isEqualTo(2);
        assertThat(streamService.activePollChannelCount()).isEqualTo(1);

        streamService.publishUpdate(update1);
        awaitCondition(() -> receivedByFirst.size() == 2 && receivedBySecond.size() == 2);

        sub1.dispose();
        assertThat(streamService.activeSubscriberCount(pollId)).isEqualTo(1);
        assertThat(streamService.activePollChannelCount()).isEqualTo(1);

        streamService.publishUpdate(update2);
        awaitCondition(() -> receivedBySecond.size() == 3);
        assertThat(receivedByFirst).hasSize(2);

        sub2.dispose();
        assertThat(streamService.activeSubscriberCount(pollId)).isZero();
        assertThat(streamService.activePollChannelCount()).isZero();
    }

    @Test
    void shouldIsolateDifferentPollsAndNoOpWhenNoSubscribers() {
        UUID pollA = UUID.randomUUID();
        UUID pollB = UUID.randomUUID();
        PollResultsResponse snapshotA = results(pollA, UUID.randomUUID(), "Poll A", 0L);
        PollResultsResponse updateB = results(pollB, UUID.randomUUID(), "Poll B", 1L);
        when(projectionService.getResults(pollA)).thenReturn(snapshotA);

        streamService.publishUpdate(updateB);
        assertThat(streamService.activePollChannelCount()).isZero();

        StepVerifier.create(streamService.streamResults(pollA))
                .assertNext(event -> assertThat(event.data()).isEqualTo(snapshotA))
                .then(() -> {
                    streamService.publishUpdate(updateB);
                    PollResultsResponse updateA = results(pollA, UUID.randomUUID(), "Poll A", 1L);
                    streamService.publishUpdate(updateA);
                })
                .assertNext(event -> {
                    PollResultsResponse payload = (PollResultsResponse) event.data();
                    assertThat(payload.pollId()).isEqualTo(pollA);
                    assertThat(payload.totalVotes()).isEqualTo(1L);
                })
                .thenCancel()
                .verify();

        assertThat(streamService.activePollChannelCount()).isZero();
    }

    @Test
    void shouldCleanUpChannelWhenPollDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(projectionService.getResults(missingId)).thenThrow(new ResultsNotFoundException("Poll results not found"));

        StepVerifier.create(streamService.streamResults(missingId))
                .expectError(ResultsNotFoundException.class)
                .verify();

        assertThat(streamService.activeSubscriberCount(missingId)).isZero();
        assertThat(streamService.activePollChannelCount()).isZero();
    }

    @Test
    void shouldRetainNewerConcurrentUpdateAndFilterAlreadyIncludedVoteDuringSnapshotLoad() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollResultsResponse snapshotAtVote1 = results(pollId, optionId, "Concurrent", 1L);
        PollResultsResponse concurrentVote1 = results(pollId, optionId, "Concurrent", 1L);
        PollResultsResponse concurrentVote2 = results(pollId, optionId, "Concurrent", 2L);

        when(projectionService.getResults(pollId)).thenAnswer(invocation -> {
            // Simulate votes committed while the JPA snapshot query is executing.
            streamService.publishUpdate(concurrentVote1);
            streamService.publishUpdate(concurrentVote2);
            return snapshotAtVote1;
        });

        StepVerifier.create(streamService.streamResults(pollId))
                .assertNext(event -> assertThat(event.data()).isEqualTo(snapshotAtVote1))
                .assertNext(event -> assertThat(event.data()).isEqualTo(concurrentVote2))
                .thenCancel()
                .verify();

        assertThat(streamService.activePollChannelCount()).isZero();
    }

    private PollResultsResponse results(UUID pollId, UUID optionId, String title, long votes) {
        return new PollResultsResponse(
                pollId,
                title,
                PollProjectionStatus.OPEN,
                votes,
                List.of(new OptionResultResponse(optionId, "Option 1", 0, votes)),
                Instant.now(),
                null
        );
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(3);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        throw new AssertionError("Timed out waiting for condition");
    }
}
