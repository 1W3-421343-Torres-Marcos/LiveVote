package com.livevote.votes.polls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.livevote.votes.common.ConflictException;
import com.livevote.votes.common.ForbiddenException;
import com.livevote.votes.common.ResourceNotFoundException;
import com.livevote.votes.common.UnprocessableEntityException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PollServiceTest {

    @Mock
    private PollRepository pollRepository;
    @Mock
    private PollOptionRepository pollOptionRepository;
    @Mock
    private VoteRepository voteRepository;

    private PollService pollService;

    @BeforeEach
    void setUp() {
        pollService = new PollService(pollRepository, pollOptionRepository, voteRepository);
    }

    @Test
    void createPollShouldSaveTrimmedOptionsInOrder() {
        UUID ownerId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        when(pollRepository.save(any(PollEntity.class))).thenAnswer(invocation -> {
            PollEntity poll = invocation.getArgument(0);
            ReflectionTestUtils.setField(poll, "id", pollId);
            return poll;
        });
        when(pollOptionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<PollOptionEntity> options = invocation.getArgument(0);
            for (PollOptionEntity option : options) {
                ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
            }
            return options;
        });

        PollResponse response = pollService.createPoll(
                ownerId,
                new CreatePollRequest(" Favorite stack? ", List.of(" Spring Boot ", " Angular "))
        );

        assertThat(response.id()).isEqualTo(pollId);
        assertThat(response.title()).isEqualTo("Favorite stack?");
        assertThat(response.status()).isEqualTo(PollStatus.OPEN);
        assertThat(response.options()).extracting(PollOptionResponse::text).containsExactly("Spring Boot", "Angular");
        assertThat(response.options()).extracting(PollOptionResponse::displayOrder).containsExactly(0, 1);
    }

    @Test
    void closePollShouldRejectNonOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        PollEntity poll = new PollEntity(ownerId, "Question", PollStatus.OPEN, Instant.now());
        ReflectionTestUtils.setField(poll, "id", pollId);

        when(pollRepository.findById(pollId)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> pollService.closePoll(pollId, otherUserId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void castVoteShouldRejectClosedPoll() {
        UUID pollId = UUID.randomUUID();
        PollEntity poll = new PollEntity(UUID.randomUUID(), "Question", PollStatus.CLOSED, Instant.now());
        ReflectionTestUtils.setField(poll, "id", pollId);

        when(pollRepository.findById(pollId)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> pollService.castVote(pollId, new CastVoteRequest(UUID.randomUUID(), "device-1")))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void castVoteShouldRejectOptionFromAnotherPoll() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollEntity poll = new PollEntity(UUID.randomUUID(), "Question", PollStatus.OPEN, Instant.now());
        ReflectionTestUtils.setField(poll, "id", pollId);

        when(pollRepository.findById(pollId)).thenReturn(Optional.of(poll));
        when(pollOptionRepository.existsByIdAndPollId(optionId, pollId)).thenReturn(false);

        assertThatThrownBy(() -> pollService.castVote(pollId, new CastVoteRequest(optionId, "device-1")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void castVoteShouldTranslateUniqueConstraintToConflict() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollEntity poll = new PollEntity(UUID.randomUUID(), "Question", PollStatus.OPEN, Instant.now());
        ReflectionTestUtils.setField(poll, "id", pollId);

        when(pollRepository.findById(pollId)).thenReturn(Optional.of(poll));
        when(pollOptionRepository.existsByIdAndPollId(optionId, pollId)).thenReturn(true);
        when(voteRepository.saveAndFlush(any(VoteEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_votes_poll_device"));

        assertThatThrownBy(() -> pollService.castVote(pollId, new CastVoteRequest(optionId, "device-1")))
                .isInstanceOf(ConflictException.class);
    }
}
