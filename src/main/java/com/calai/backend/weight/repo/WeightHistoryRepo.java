package com.calai.backend.weight.repo;

import com.calai.backend.weight.entity.WeightHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.*;

@Repository
public interface WeightHistoryRepo extends JpaRepository<WeightHistory, Long> {

    Optional<WeightHistory> findByUserIdAndLogDate(Long userId, LocalDate logDate);

    // ✅ 保留：給 ensureBaselineFromProfile / history7Latest 用
    @Query("""
           select w from WeightHistory w 
           where w.userId = :uid
           order by w.logDate desc
           """)
    List<WeightHistory> findAllByUserDesc(Long uid, Pageable pageable);

}
