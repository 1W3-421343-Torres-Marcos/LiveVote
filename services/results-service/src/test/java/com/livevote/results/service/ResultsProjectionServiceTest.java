package com.livevote.results.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.entity.OptionCountEntity;
import com.livevote.results.entity.PollProjectionEntity;
import com.livevote.results.entity.PollProjectionStatus;
import com.livevote.results.event.PollCreatedEvent;
import com.livevote.results.event.PollCreatedOptionEvent;
import com.livevote.results.event.VoteCastEvent;
import com.livevote.results.exception.InvalidEventException;
import com.livevote.results.exception.PollProjectionPendingException;
import com.livevote.results.exception.ResultsNotFoundException;
import com.livevote.results.repository.OptionCountRepository;
import com.livevote.results.repository.PollProjectionRepository;
import com.livevote.results.repository.ProcessedVoteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResultsProjectionServiceTest {

    @Mock
    private PollProjectionRepository pollProjectionRepository;
    @Mock
    private OptionCountRepository optionCountRepository;
    @Mock
    private ProcessedVoteRepository processedVoteRepository;

    private ResultsProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ResultsProjectionService(
                pollProjectionRepository,
                optionCountRepository,
                processedVoteRepository
        );
    }

    @Test
    void handlePollCreatedShouldPersistProjectionAndZeroedOptions() {
        UUID pollId = UUID.randomUUID();
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();
        PollCreatedEvent event = new PollCreatedEvent(
                1,
                pollId,
                "Best IDE?",
                List.of(new PollCreatedOptionEvent(opt1, "IntelliJ"), new PollCreatedOptionEvent(opt2, "VS Code")),
                Instant.now()
        );
        when(pollProjectionRepository.existsById(pollId)).thenReturn(false);

        service.handlePollCreated(event);

        verify(pollProjectionRepository).save(any(PollProjectionEntity.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OptionCountEntity>> optionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(optionCountRepository).saveAll(optionsCaptor.capture());

        List<OptionCountEntity> savedOptions = optionsCaptor.getValue();
        assertThat(savedOptions).hasSize(2);
        assertThat(savedOptions).extracting(OptionCountEntity::getVoteCount).containsExactly(0L, 0L);
        assertThat(savedOptions).extracting(OptionCountEntity::getDisplayOrder).containsExactly(0, 1);
    }

    @Test
    void handlePollCreatedShouldIgnoreDuplicatePollId() {
        UUID pollId = UUID.randomUUID();
        PollCreatedEvent event = new PollCreatedEvent(
                1,
                pollId,
                "Best IDE?",
                List.of(new PollCreatedOptionEvent(UUID.randomUUID(), "IntelliJ")),
                Instant.now()
        );
        when(pollProjectionRepository.existsById(pollId)).thenReturn(true);

        service.handlePollCreated(event);

        verify(pollProjectionRepository, never()).save(any());
        verify(optionCountRepository, never()).saveAll(any());
    }

    @Test
    void handleVoteCastShouldThrowPendingWhenPollProjectionNotCreatedYet() {
        UUID pollId = UUID.randomUUID();
        VoteCastEvent event = new VoteCastEvent(1, UUID.randomUUID(), pollId, UUID.randomUUID(), Instant.now());
        when(pollProjectionRepository.existsById(pollId)).thenReturn(false);

        assertThatThrownBy(() -> service.handleVoteCast(event))
                .isInstanceOf(PollProjectionPendingException.class);
        verify(processedVoteRepository, never()).insertIgnoreDuplicate(any(), any(), any());
    }

    @Test
    void handleVoteCastShouldIncrementCountOnceAndIgnoreDuplicateVoteId() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID voteId = UUID.randomUUID();
        VoteCastEvent event = new VoteCastEvent(1, voteId, pollId, optionId, Instant.now());

        when(pollProjectionRepository.existsById(pollId)).thenReturn(true);
        when(processedVoteRepository.insertIgnoreDuplicate(any(), any(), any())).thenReturn(1, 0);
        when(optionCountRepository.incrementVoteCount(optionId, pollId)).thenReturn(1);

        service.handleVoteCast(event);
        service.handleVoteCast(event);

        verify(optionCountRepository).incrementVoteCount(optionId, pollId);
    }

    @Test
    void handleVoteCastShouldRejectUnknownOptionWithinExistingPoll() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID voteId = UUID.randomUUID();
        VoteCastEvent event = new VoteCastEvent(1, voteId, pollId, optionId, Instant.now());

        when(pollProjectionRepository.existsById(pollId)).thenReturn(true);
        when(processedVoteRepository.insertIgnoreDuplicate(any(), any(), any())).thenReturn(1);
        when(optionCountRepository.incrementVoteCount(optionId, pollId)).thenReturn(0);

        assertThatThrownBy(() -> service.handleVoteCast(event))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void getResultsShouldReturnAggregatedTotalAndThrowNotFoundWhenMissing() {
        UUID pollId = UUID.randomUUID();
        Instant now = Instant.now();
        PollProjectionEntity projection = new PollProjectionEntity(pollId, "Favorite?", PollProjectionStatus.OPEN, now);
        when(pollProjectionRepository.findById(pollId)).thenReturn(Optional.of(projection));
        when(optionCountRepository.findAllByPollIdOrderByDisplayOrderAsc(pollId)).thenReturn(List.of(
                new OptionCountEntity(UUID.randomUUID(), pollId, "A", 0, 3L),
                new OptionCountEntity(UUID.randomUUID(), pollId, "B", 1, 5L)
        ));

        PollResultsResponse response = service.getResults(pollId);

        assertThat(response.totalVotes()).isEqualTo(8L);
        assertThat(response.options()).hasSize(2);

        UUID missingId = UUID.randomUUID();
        when(pollProjectionRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getResults(missingId))
                .isInstanceOf(ResultsNotFoundException.class);
    }
}
