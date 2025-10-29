package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkoutSessionRepo extends JpaRepository<WorkoutSession, Long> {

    @Query("""
        select ws from WorkoutSession ws
        join fetch ws.dictionary d
        where ws.userId = ?1
          and ws.startedAt between ?2 and ?3
        order by ws.startedAt desc
        """)
    List<WorkoutSession> findUserSessionsBetween(Long userId, Instant start, Instant end);

    @Query("""
        select ws from WorkoutSession ws
        where ws.id = ?1 and ws.userId = ?2
        """)
    Optional<WorkoutSession> findByIdAndUserId(Long sessionId, Long userId);
}