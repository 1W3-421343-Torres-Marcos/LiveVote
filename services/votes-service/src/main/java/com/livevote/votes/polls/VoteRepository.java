package com.livevote.votes.polls;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for votes.
 */
public interface VoteRepository extends JpaRepository<VoteEntity, UUID> {
}
