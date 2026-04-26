package com.calai.backend.entitlement.repo;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserEntitlementRepository extends JpaRepository<UserEntitlementEntity, String> {

    Optional<UserEntitlementEntity> findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(String purchaseTokenHash);

    Optional<UserEntitlementEntity> findTopByLinkedPurchaseTokenHashOrderByUpdatedAtUtcDesc(
            String linkedPurchaseTokenHash
    );


    @Query("""
        select e from UserEntitlementEntity e
        where e.userId = :userId
          and e.status = 'ACTIVE'
          and e.validFromUtc <= :now
          and e.validToUtc > :now
        order by e.validToUtc desc
    """)
    List<UserEntitlementEntity> findActive(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        select e from UserEntitlementEntity e
        where e.userId = :userId
          and e.status = 'ACTIVE'
          and e.validFromUtc <= :now
          and e.validToUtc > :now
        order by
          case e.entitlementType
            when 'YEARLY' then 3
            when 'MONTHLY' then 2
            when 'TRIAL' then 1
            else 0
          end desc,
          e.validToUtc desc
    """)
    List<UserEntitlementEntity> findActiveBestFirst(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntitlementEntity e
           set e.status = :status,
               e.updatedAtUtc = :now,
               e.revokedAtUtc = :revokedAtUtc,
               e.lastRtdnAtUtc = :lastRtdnAtUtc,
               e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
         where e.purchaseTokenHash = :purchaseTokenHash
           and e.status = 'ACTIVE'
    """)
    int closeActiveByPurchaseTokenHash(
            @Param("purchaseTokenHash") String purchaseTokenHash,
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("revokedAtUtc") Instant revokedAtUtc,
            @Param("lastRtdnAtUtc") Instant lastRtdnAtUtc
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntitlementEntity e
           set e.status = 'EXPIRED',
               e.updatedAtUtc = :now
         where e.status = 'ACTIVE'
           and e.validToUtc <= :now
    """)
    int expireAllEndedEntitlements(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    update UserEntitlementEntity e
       set e.status = 'EXPIRED',
           e.updatedAtUtc = :now,
           e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
     where e.userId = :userId
       and e.status = 'ACTIVE'
       and e.id <> :keepId
""")
    int expireActiveByUserIdExcept(
            @Param("userId") Long userId,
            @Param("keepId") String keepId,
            @Param("now") Instant now
    );

}
