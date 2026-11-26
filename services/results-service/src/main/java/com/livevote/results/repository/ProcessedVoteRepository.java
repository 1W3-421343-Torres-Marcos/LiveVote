package com.livevote.results.repository;

import com.livevote.results.entity.ProcessedVoteEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for idempotent vote processing records.
 */
public interface ProcessedVoteRepository extends JpaRepository<ProcessedVoteEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_votes (vote_id, poll_id, processed_at)
            VALUES (:voteId, :pollId, :processedAt)
            ON CONFLICT (vote_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("voteId") UUID voteId,
            @Param("pollId") UUID pollId,
            @Param("processedAt") Instant processedAt
    );
}
