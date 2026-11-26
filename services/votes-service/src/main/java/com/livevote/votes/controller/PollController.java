package com.livevote.votes.controller;

import com.livevote.votes.dto.CastVoteRequest;
import com.livevote.votes.dto.CreatePollRequest;
import com.livevote.votes.dto.PollResponse;
import com.livevote.votes.dto.VoteResponse;
import com.livevote.votes.exception.UnauthorizedException;
import com.livevote.votes.service.PollService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for presenter poll management and public anonymous voting.
 */
@RestController
@RequestMapping("/api/polls")
public class PollController {

    private final PollService pollService;

    public PollController(PollService pollService) {
        this.pollService = pollService;
    }

    @PostMapping
    public ResponseEntity<PollResponse> createPoll(
            @AuthenticationPrincipal UUID ownerId,
            @Valid @RequestBody CreatePollRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pollService.createPoll(requireUserId(ownerId), request));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<PollResponse>> getMyPolls(@AuthenticationPrincipal UUID ownerId) {
        return ResponseEntity.ok(pollService.getMyPolls(requireUserId(ownerId)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<PollResponse> closePoll(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID ownerId
    ) {
        return ResponseEntity.ok(pollService.closePoll(id, requireUserId(ownerId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PollResponse> getPublicPoll(@PathVariable UUID id) {
        return ResponseEntity.ok(pollService.getPublicPoll(id));
    }

    @PostMapping("/{id}/votes")
    public ResponseEntity<VoteResponse> castVote(
            @PathVariable UUID id,
            @Valid @RequestBody CastVoteRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pollService.castVote(id, request));
    }

    private UUID requireUserId(UUID userId) {
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return userId;
    }
}
