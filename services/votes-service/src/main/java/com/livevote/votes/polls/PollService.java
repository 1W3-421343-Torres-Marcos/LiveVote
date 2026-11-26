package com.livevote.votes.polls;

import com.livevote.votes.common.ConflictException;
import com.livevote.votes.common.ForbiddenException;
import com.livevote.votes.common.ResourceNotFoundException;
import com.livevote.votes.common.UnprocessableEntityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for poll management and single-vote-per-device enforcement.
 */
@Service
public class PollService {

    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final VoteRepository voteRepository;

    public PollService(
            PollRepository pollRepository,
            PollOptionRepository pollOptionRepository,
            VoteRepository voteRepository
    ) {
        this.pollRepository = pollRepository;
        this.pollOptionRepository = pollOptionRepository;
        this.voteRepository = voteRepository;
    }

    @Transactional
    public PollResponse createPoll(UUID ownerId, CreatePollRequest request) {
        PollEntity poll = new PollEntity(ownerId, request.title().trim(), PollStatus.OPEN, Instant.now());
        PollEntity savedPoll = pollRepository.save(poll);

        List<PollOptionEntity> optionsToSave = new ArrayList<>(request.options().size());
        for (int i = 0; i < request.options().size(); i++) {
            optionsToSave.add(new PollOptionEntity(savedPoll.getId(), request.options().get(i).trim(), i));
        }
        List<PollOptionEntity> savedOptions = pollOptionRepository.saveAll(optionsToSave);
        return toPollResponse(savedPoll, savedOptions);
    }

    @Transactional(readOnly = true)
    public List<PollResponse> getMyPolls(UUID ownerId) {
        List<PollEntity> polls = pollRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        if (polls.isEmpty()) {
            return List.of();
        }

        List<UUID> pollIds = polls.stream().map(PollEntity::getId).toList();
        Map<UUID, List<PollOptionEntity>> optionsByPoll = pollOptionRepository
                .findAllByPollIdInOrderByDisplayOrderAsc(pollIds)
                .stream()
                .collect(Collectors.groupingBy(PollOptionEntity::getPollId));

        return polls.stream()
                .map(poll -> toPollResponse(poll, optionsByPoll.getOrDefault(poll.getId(), List.of())))
                .toList();
    }

    @Transactional
    public PollResponse closePoll(UUID pollId, UUID ownerId) {
        PollEntity poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll not found"));

        if (!poll.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("Only the poll owner can close this poll");
        }

        poll.close(Instant.now());
        PollEntity savedPoll = pollRepository.save(poll);
        List<PollOptionEntity> options = pollOptionRepository.findAllByPollIdOrderByDisplayOrderAsc(pollId);
        return toPollResponse(savedPoll, options);
    }

    @Transactional(readOnly = true)
    public PollResponse getPublicPoll(UUID pollId) {
        PollEntity poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll not found"));
        List<PollOptionEntity> options = pollOptionRepository.findAllByPollIdOrderByDisplayOrderAsc(pollId);
        return toPollResponse(poll, options);
    }

    @Transactional
    public VoteResponse castVote(UUID pollId, CastVoteRequest request) {
        PollEntity poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll not found"));

        if (poll.getStatus() == PollStatus.CLOSED) {
            throw new UnprocessableEntityException("Poll is closed");
        }

        if (!pollOptionRepository.existsByIdAndPollId(request.optionId(), pollId)) {
            throw new ResourceNotFoundException("Poll option not found");
        }

        VoteEntity vote = new VoteEntity(pollId, request.optionId(), request.deviceId().trim(), Instant.now());
        try {
            VoteEntity savedVote = voteRepository.saveAndFlush(vote);
            return new VoteResponse(
                    savedVote.getId(),
                    savedVote.getPollId(),
                    savedVote.getOptionId(),
                    savedVote.getCastAt()
            );
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Device has already voted on this poll");
        }
    }

    private PollResponse toPollResponse(PollEntity poll, List<PollOptionEntity> options) {
        List<PollOptionResponse> optionResponses = options.stream()
                .map(option -> new PollOptionResponse(option.getId(), option.getText(), option.getDisplayOrder()))
                .toList();
        return new PollResponse(
                poll.getId(),
                poll.getTitle(),
                poll.getStatus(),
                optionResponses,
                poll.getCreatedAt(),
                poll.getClosedAt()
        );
    }
}
