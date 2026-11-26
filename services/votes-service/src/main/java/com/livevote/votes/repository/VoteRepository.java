package com.livevote.votes.repository;

import com.livevote.votes.entity.VoteEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for votes.
 */
public interface VoteRepository extends JpaRepository<VoteEntity, UUID> {
}
