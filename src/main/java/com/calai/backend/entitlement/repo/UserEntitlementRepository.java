package com.calai.backend.entitlement.repo;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserEntitlementRepository extends JpaRepository<UserEntitlementEntity, String> {

    @Query("""
        select e from UserEntitlementEntity e
        where e.userId = :userId
          and e.status = 'ACTIVE'
          and e.validFromUtc <= :now
          and e.validToUtc > :now
        order by e.validToUtc desc
    """)
    List<UserEntitlementEntity> findActive(@Param("userId") Long userId, @Param("now") Instant now, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    update UserEntitlementEntity e
       set e.status = 'EXPIRED',
           e.updatedAtUtc = :now,
           e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
     where e.userId = :userId
       and e.status = 'ACTIVE'
       and e.validToUtc > :now
""")
    int expireActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
