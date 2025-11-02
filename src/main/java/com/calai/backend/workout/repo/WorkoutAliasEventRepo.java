package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutAliasEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WorkoutAliasEventRepo extends JpaRepository<WorkoutAliasEvent, Long> {

    @Query("""
        select e from WorkoutAliasEvent e
        where e.createdAt >= :since
    """)
    List<WorkoutAliasEvent> findSince(@Param("since") Instant since);

    // ★ 新增：統計某用戶在時間窗內輸入的「不同片語」數（distinct phraseLower）
    @Query("""
        select count(distinct e.phraseLower) 
        from WorkoutAliasEvent e
        where e.userId = :uid and e.createdAt >= :since
    """)
    long countDistinctPhrasesSince(@Param("uid") Long userId, @Param("since") Instant since);


}
