package com.livevote.results.repository;

import com.livevote.results.entity.OptionCountEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for projected option vote counts.
 */
public interface OptionCountRepository extends JpaRepository<OptionCountEntity, UUID> {

    List<OptionCountEntity> findAllByPollIdOrderByDisplayOrderAsc(UUID pollId);

    @Modifying
    @Query("""
            UPDATE OptionCountEntity o
            SET o.voteCount = o.voteCount + 1
            WHERE o.optionId = :optionId AND o.pollId = :pollId
            """)
    int incrementVoteCount(@Param("optionId") UUID optionId, @Param("pollId") UUID pollId);
}
