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

    @Query("""
        select distinct w.userId
        from WeightHistory w
        where w.photoUrl is not null
    """)
    List<Long> findUserIdsWithPhotos();

    @Query("""
        select w
        from WeightHistory w
        where w.userId = :uid and w.photoUrl is not null
        order by w.logDate desc, w.id desc
    """)
    List<WeightHistory> findPhotosByUserDesc(Long uid, Pageable pageable);

    @Query("""
        select w.photoUrl
        from WeightHistory w
        where w.photoUrl is not null
    """)
    List<String> findAllPhotoUrls();

}
