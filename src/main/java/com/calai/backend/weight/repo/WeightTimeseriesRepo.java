package com.calai.backend.weight.repo;// package com.calai.backend.weight.repo;

import com.calai.backend.weight.entity.WeightTimeseries;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeightTimeseriesRepo extends JpaRepository<WeightTimeseries, Long> {
    Optional<WeightTimeseries> findByUserIdAndLogDate(Long userId, LocalDate logDate);

    @Query("""
           select w from WeightTimeseries w 
           where w.userId = :uid and w.logDate between :start and :end
           order by w.logDate asc
           """)
    List<WeightTimeseries> findRange(Long uid, LocalDate start, LocalDate end);

    @Query("""
           select w from WeightTimeseries w
           where w.userId = :uid
           order by w.logDate desc
           """)
    List<WeightTimeseries> findLatest(Long uid, org.springframework.data.domain.Pageable pageable);

    // ★ 新增：抓「最早幾筆」體重紀錄（依日期由小到大排序）
    @Query("""
       select w from WeightTimeseries w
       where w.userId = :uid
       order by w.logDate asc
       """)
    List<WeightTimeseries> findEarliest(Long uid, org.springframework.data.domain.Pageable pageable);
}
