package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface WorkoutAliasRepo extends JpaRepository<WorkoutAlias, Long> {

    @Query("""
        select wa from WorkoutAlias wa
        join fetch wa.dictionary d
        where lower(wa.phraseLower)=lower(?1)
          and wa.langTag = ?2
          and wa.status = 'APPROVED'
        """)
    Optional<WorkoutAlias> findApprovedAlias(String phraseLower, String langTag);
}