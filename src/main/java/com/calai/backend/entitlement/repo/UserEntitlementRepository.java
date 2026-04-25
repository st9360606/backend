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

    /**
     * 付款成功後使用：
     * 將該 user 所有 ACTIVE entitlement 關閉。
     *
     * 包含：
     * - TRIAL ACTIVE
     * - MONTHLY ACTIVE
     * - YEARLY ACTIVE
     *
     * 注意：
     * 這裡故意不限制 validToUtc > now，
     * 因為過期但尚未被 worker 清理的 TRIAL，也要在付款成功後改成 EXPIRED。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntitlementEntity e
           set e.status = 'EXPIRED',
               e.updatedAtUtc = :now,
               e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
         where e.userId = :userId
           and e.status = 'ACTIVE'
    """)
    int expireActiveByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    /**
     * 保留給只想關閉 paid entitlement 的舊流程使用。
     * 新的付款 sync 主流程請使用 expireActiveByUserId。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntitlementEntity e
           set e.status = 'EXPIRED',
               e.updatedAtUtc = :now,
               e.validToUtc = case when e.validToUtc > :now then :now else e.validToUtc end
         where e.userId = :userId
           and e.status = 'ACTIVE'
           and e.validToUtc > :now
           and e.entitlementType in ('MONTHLY', 'YEARLY')
    """)
    int expireActivePaidByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    /**
     * Trial / paid 過期後的資料清理：
     * API 判斷是否有效仍以 validToUtc > now 為準；
     * 這個方法主要是避免 DB 顯示 ACTIVE 但其實已過期。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update UserEntitlementEntity e
           set e.status = 'EXPIRED',
               e.updatedAtUtc = :now
         where e.status = 'ACTIVE'
           and e.validToUtc <= :now
    """)
    int expireAllEndedEntitlements(@Param("now") Instant now);

    /**
     * Trial 防重複領取：
     * 只要歷史上曾經有 TRIAL，就不再發第二次。
     */
    boolean existsByUserIdAndEntitlementType(Long userId, String entitlementType);
}
