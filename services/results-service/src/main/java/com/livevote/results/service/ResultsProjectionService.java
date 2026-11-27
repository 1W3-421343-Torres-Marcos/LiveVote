package com.livevote.results.service;

import com.livevote.results.dto.OptionResultResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains read projections in postgres-results with atomic vote deduplication.
 */
@Service
public class ResultsProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ResultsProjectionService.class);

    private final PollProjectionRepository pollProjectionRepository;
    private final OptionCountRepository optionCountRepository;
    private final ProcessedVoteRepository processedVoteRepository;

    public ResultsProjectionService(
            PollProjectionRepository pollProjectionRepository,
            OptionCountRepository optionCountRepository,
            ProcessedVoteRepository processedVoteRepository
    ) {
        this.pollProjectionRepository = pollProjectionRepository;
        this.optionCountRepository = optionCountRepository;
        this.processedVoteRepository = processedVoteRepository;
    }

    @Transactional
    public void handlePollCreated(PollCreatedEvent event) {
        if (pollProjectionRepository.existsById(event.pollId())) {
            log.debug("Ignoring duplicate PollCreated pollId={}", event.pollId());
            return;
        }

        PollProjectionEntity projection = new PollProjectionEntity(
                event.pollId(),
                event.title(),
                PollProjectionStatus.OPEN,
                event.createdAt()
        );
        pollProjectionRepository.save(projection);

        List<OptionCountEntity> options = new ArrayList<>(event.options().size());
        for (int i = 0; i < event.options().size(); i++) {
            PollCreatedOptionEvent option = event.options().get(i);
            options.add(new OptionCountEntity(
                    option.optionId(),
                    event.pollId(),
                    option.text(),
                    i,
                    0L
            ));
        }
        optionCountRepository.saveAll(options);
    }

    @Transactional
    public Optional<PollResultsResponse> handleVoteCast(VoteCastEvent event) {
        if (!pollProjectionRepository.existsById(event.pollId())) {
            throw new PollProjectionPendingException("Poll projection not found yet for pollId=" + event.pollId());
        }

        int inserted = processedVoteRepository.insertIgnoreDuplicate(
                event.voteId(),
                event.pollId(),
                Instant.now()
        );
        if (inserted == 0) {
            log.debug("Ignoring duplicate VoteCast voteId={}", event.voteId());
            return Optional.empty();
        }

        int updated = optionCountRepository.incrementVoteCount(event.optionId(), event.pollId());
        if (updated == 0) {
            throw new InvalidEventException("Option not found in poll projection: optionId=" + event.optionId());
        }
        return Optional.of(getResults(event.pollId()));
    }

    @Transactional(readOnly = true)
    public PollResultsResponse getResults(UUID pollId) {
        PollProjectionEntity poll = pollProjectionRepository.findById(pollId)
                .orElseThrow(() -> new ResultsNotFoundException("Poll results not found"));

        List<OptionCountEntity> optionEntities = optionCountRepository.findAllByPollIdOrderByDisplayOrderAsc(pollId);
        List<OptionResultResponse> options = optionEntities.stream()
                .map(option -> new OptionResultResponse(
                        option.getOptionId(),
                        option.getText(),
                        option.getDisplayOrder(),
                        option.getVoteCount()
                ))
                .toList();

        long totalVotes = options.stream().mapToLong(OptionResultResponse::votes).sum();

        return new PollResultsResponse(
                poll.getPollId(),
                poll.getTitle(),
                poll.getStatus(),
                totalVotes,
                options,
                poll.getCreatedAt(),
                poll.getClosedAt()
        );
    }
}
