package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkoutAliasRepo extends JpaRepository<WorkoutAlias, Long> {

    @Query("""
        select a from WorkoutAlias a
        where a.langTag = :lang and a.phraseLower = :phrase and a.status = 'APPROVED'
    """)
    Optional<WorkoutAlias> findApprovedAlias(@Param("phrase") String phrase, @Param("lang") String lang);

    // ★ 新增：不看狀態（用於就地升級，避免重複插入）
    @Query("""
        select a from WorkoutAlias a
        where a.langTag = :lang and a.phraseLower = :phrase
    """)
    Optional<WorkoutAlias> findAnyByLangAndPhrase(@Param("lang") String lang, @Param("phrase") String phrase);
}
