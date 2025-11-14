package com.calai.backend.weight.repo;

import com.calai.backend.weight.entity.WeightHistory;
import com.calai.backend.weight.entity.WeightTimeseries;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface WeightHistoryRepo extends JpaRepository<WeightHistory, Long> {
    Optional<WeightHistory> findByUserIdAndLogDate(Long userId, LocalDate logDate);

    @Query("""
           select w from WeightHistory w 
           where w.userId = :uid and w.logDate < :today 
           order by w.logDate desc
           """)
    List<WeightHistory> findLatestBeforeToday(Long uid, LocalDate today, org.springframework.data.domain.Pageable pageable);

    @Query("""
           select w from WeightHistory w 
           where w.userId = :uid
           order by w.logDate desc
           """)
    List<WeightHistory> findAllByUserDesc(Long uid, org.springframework.data.domain.Pageable pageable);
}


